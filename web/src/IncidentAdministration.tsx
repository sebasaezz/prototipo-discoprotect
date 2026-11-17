import { useEffect, useRef, useState } from 'react';
import { activeIncident, ApiError, previewDemoPurge, purgeDemo, withdrawIncident, type Incident, type PurgeCommand,
  type Session, type Withdrawal } from './api.js';
import { IncidentsPage } from './IncidentsPage.js';

type Pending =
  | { kind: 'purge'; count: number; command: PurgeCommand }
  | { kind: 'withdraw'; incident: Incident; command: Withdrawal };

function operationId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function IncidentAdministration({ visible, items, session, event, onExpiry, onAccessLost, refresh }: {
  visible: boolean; items: Incident[]; session: Session; event: string;
  onExpiry: () => void; onAccessLost: () => void; refresh: () => void;
}) {
  const [purgeEnabled, setPurgeEnabled] = useState(false);
  const [pending, setPending] = useState<Pending | null>(null);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [retry, setRetry] = useState(false);
  const [notice, setNotice] = useState('');
  const confirmationField = useRef<HTMLInputElement>(null);
  const pendingId = pending?.command.operationId;

  useEffect(() => {
    if (visible && pendingId) confirmationField.current?.focus();
  }, [visible, pendingId]);

  useEffect(() => {
    const abort = new AbortController();
    previewDemoPurge(session, event, AbortSignal.any([abort.signal, AbortSignal.timeout(8000)]))
      .then(result => { if (!abort.signal.aborted) setPurgeEnabled(result.enabled); })
      .catch(error => {
        if (abort.signal.aborted || !(error instanceof ApiError)) return;
        if (error.status === 401) onExpiry();
        if (error.status === 403) onAccessLost();
      });
    return () => abort.abort();
  }, [session, event, onExpiry, onAccessLost]);

  function failure(error: unknown) {
    if (error instanceof ApiError && error.status === 401) onExpiry();
    setNotice(error instanceof ApiError ? error.message : 'No se pudo confirmar la operación. Puedes reintentar.');
  }

  async function openPurge(id?: string) {
    setBusy(true);
    setNotice('');
    try {
      const next = await previewDemoPurge(session, event, AbortSignal.timeout(8000), id);
      setPurgeEnabled(next.enabled);
      if (next.enabled && next.count > 0 && next.selectionHash) {
        setInput('');
        setRetry(false);
        setPending({ kind: 'purge', count: next.count, command: { incidentId: id, confirmation: 'ELIMINAR',
          operationId: operationId(), selectionHash: next.selectionHash } });
      } else setNotice(next.enabled ? 'No hay solicitudes para eliminar.' : 'La eliminación de demo está desactivada.');
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) onAccessLost();
      else failure(error);
    }
    finally { setBusy(false); }
  }

  async function submit() {
    if (!pending) return;
    const operation: Pending = pending.kind === 'withdraw' && !retry
      ? { ...pending, command: { ...pending.command, resolutionNote: input.trim() } } : pending;
    setPending(operation);
    setBusy(true);
    try {
      if (operation.kind === 'withdraw') {
        await withdrawIncident(session, operation.incident, operation.command);
        setNotice('Solicitud retirada de la cola.');
      } else {
        const receipt = await purgeDemo(session, event, operation.command);
        setNotice(`${receipt.count} solicitudes eliminadas. Respaldo verificado: ${receipt.backupName}`);
      }
      setPending(null);
      refresh();
    } catch (error) {
      if (operation.kind === 'withdraw' && error instanceof ApiError && error.status === 403) {
        onAccessLost();
        return;
      }
      failure(error);
      if (error instanceof ApiError && error.status < 500) {
        setPending(null);
        refresh();
      } else setRetry(true);
    } finally { setBusy(false); }
  }

  // Keep the command and any uncertain receipt while the organizer visits another section.
  if (!visible) return null;

  const rowActions = (item: Incident) => <>
    {activeIncident(item) && <button className="secondary" disabled={busy || !!pending} onClick={() => {
      setInput('');
      setRetry(false);
      setNotice('');
      setPending({ kind: 'withdraw', incident: item, command: {
        version: item.version, operationId: operationId(), resolutionNote: '',
      } });
    }}>Retirar de la cola</button>}
    {purgeEnabled && <button className="secondary" disabled={busy || !!pending}
      onClick={() => void openPurge(item.id)}>Eliminar definitivamente</button>}
  </>;
  return <IncidentsPage items={items} session={session} onExpiry={onExpiry} onAccessLost={onAccessLost}
    rowActions={rowActions} purgeEnabled={purgeEnabled}>
    {purgeEnabled && <div className="incident-tools">
      <button className="secondary" disabled={busy || !!pending || !items.length}
        onClick={() => void openPurge()}>Limpiar solicitudes demo</button>
    </div>}
    {notice && <p role="status">{notice}</p>}
    {pending && <form className="notice incident-confirmation" onSubmit={event => { event.preventDefault(); void submit(); }}>
      <h2>{pending.kind === 'purge' ? 'Confirmar eliminación definitiva' : 'Confirmar retiro de la cola'}</h2>
      {pending.kind === 'purge' && <p>Se eliminarán {pending.count} solicitudes tras verificar un respaldo.</p>}
      <label htmlFor="incident-confirmation">{pending.kind === 'purge' ? 'Escribe ELIMINAR' : 'Motivo del retiro'}</label>
      <input id="incident-confirmation" ref={confirmationField} value={input} onChange={event => setInput(event.target.value)}
        readOnly={busy || retry} maxLength={240} />
      <div className="confirmation-actions">
        <button disabled={busy || (pending.kind === 'purge' ? input !== 'ELIMINAR' : !input.trim())}>
          {retry ? 'Reintentar la misma operación' : 'Confirmar'}
        </button>
        <button type="button" className="secondary" disabled={busy} onClick={() => setPending(null)}>Volver</button>
      </div>
    </form>}
  </IncidentsPage>;
}

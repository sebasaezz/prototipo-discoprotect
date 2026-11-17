import { useEffect, useState } from 'react';
import { activeIncident, ApiError, previewDemoPurge, purgeDemo, withdrawIncident, type Incident, type PurgeCommand,
  type Session, type Withdrawal } from './api.js';
import { IncidentTable } from './IncidentTable.js';

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

export function IncidentAdministration({ items, session, event, onExpiry, refresh }: {
  items: Incident[]; session: Session; event: string; onExpiry: () => void; refresh: () => void;
}) {
  const [purgeEnabled, setPurgeEnabled] = useState(false);
  const [pending, setPending] = useState<Pending | null>(null);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [retry, setRetry] = useState(false);
  const [notice, setNotice] = useState('');
  useEffect(() => {
    const abort = new AbortController();
    previewDemoPurge(session, event, AbortSignal.any([abort.signal, AbortSignal.timeout(8000)]))
      .then(result => { if (!abort.signal.aborted) setPurgeEnabled(result.enabled); })
      .catch(() => {});
    return () => abort.abort();
  }, [session, event]);

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
    } catch (error) { failure(error); }
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
      failure(error);
      if (error instanceof ApiError && error.status < 500) {
        setPending(null);
        refresh();
      } else setRetry(true);
    } finally { setBusy(false); }
  }

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
  const active = items.filter(activeIncident);
  const history = items.filter(item => !activeIncident(item));
  return <>
    {purgeEnabled && <button className="secondary" disabled={busy || !!pending || !items.length}
      onClick={() => void openPurge()}>Limpiar solicitudes demo</button>}
    {notice && <p role="status">{notice}</p>}
    {pending && <form className="notice" onSubmit={event => { event.preventDefault(); void submit(); }}>
      <h3>{pending.kind === 'purge' ? 'Confirmar eliminación definitiva' : 'Confirmar retiro de la cola'}</h3>
      {pending.kind === 'purge' && <p>Se eliminarán {pending.count} solicitudes tras verificar un respaldo.</p>}
      <label htmlFor="incident-confirmation">{pending.kind === 'purge' ? 'Escribe ELIMINAR' : 'Motivo del retiro'}</label>
      <input id="incident-confirmation" value={input} onChange={event => setInput(event.target.value)}
        readOnly={busy || retry} maxLength={240} />
      <button disabled={busy || (pending.kind === 'purge' ? input !== 'ELIMINAR' : !input.trim())}>
        {retry ? 'Reintentar la misma operación' : 'Confirmar'}
      </button>
      <button type="button" className="secondary" disabled={busy} onClick={() => setPending(null)}>Volver</button>
    </form>}
    <h3>Cola activa</h3>
    <IncidentTable items={active} session={session} onExpiry={onExpiry} actions={rowActions} />
    {history.length > 0 && <><h3>Historial</h3>
      <IncidentTable items={history} session={session} onExpiry={onExpiry} actions={purgeEnabled ? rowActions : undefined} /></>}
  </>;
}

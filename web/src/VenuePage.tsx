import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, messageFor, type Session } from './api.js';
import { pointKinds, readVenue, writeVenue, zoneKinds, type VenueChange, type VenueConfiguration } from './venueApi.js';
import { VenueForm, type VenueEditor } from './VenueForms.js';

export function VenuePage({ session, eventId, onExpiry, onVenueRead }: {
  session: Session; eventId: string; onExpiry: () => void;
  onVenueRead: (eventId: string, venue: VenueConfiguration['venue']) => void;
}) {
  const [config, setConfig] = useState<VenueConfiguration | null>(null);
  const [editor, setEditor] = useState<VenueEditor | null>(null);
  const [busy, setBusy] = useState(false);
  const [reviewRequired, setReviewRequired] = useState(false);
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const active = useRef<AbortController | null>(null);
  const formHeading = useRef<HTMLDivElement>(null);

  const report = useCallback((failure: unknown) => {
    if (failure instanceof ApiError && failure.status === 401) {
      onExpiry();
      return;
    }
    if (failure instanceof ApiError && failure.status === 403) {
      setConfig(null);
      setEditor(null);
    }
    setError(messageFor(failure));
  }, [onExpiry]);

  const reload = useCallback(async () => {
    if (active.current) return;
    const request = new AbortController();
    active.current = request;
    setBusy(true);
    setError('');
    try {
      const data = await readVenue(session, eventId, AbortSignal.any([request.signal, AbortSignal.timeout(8000)]));
      if (request.signal.aborted) return;
      setConfig(data);
      setEditor(null);
      setReviewRequired(false);
      onVenueRead(eventId, data.venue);
    } catch (failure) {
      if (!request.signal.aborted) report(failure);
    } finally {
      if (active.current === request) {
        active.current = null;
        setBusy(false);
      }
    }
  }, [eventId, onVenueRead, report, session]);

  useEffect(() => {
    void reload();
    return () => {
      active.current?.abort();
      active.current = null;
    };
  }, [reload]);

  useEffect(() => {
    if (editor) formHeading.current?.focus();
  }, [editor]);

  async function save(change: VenueChange) {
    if (active.current || !config || reviewRequired) return;
    const request = new AbortController();
    active.current = request;
    setBusy(true);
    setError('');
    setNotice('');
    let confirmed = false;
    try {
      await writeVenue(session, eventId, config.version, change, AbortSignal.any([request.signal, AbortSignal.timeout(8000)]));
      if (request.signal.aborted) return;
      confirmed = true;
      setEditor(null);
      setNotice('Cambios guardados.');
      const data = await readVenue(session, eventId, AbortSignal.any([request.signal, AbortSignal.timeout(8000)]));
      if (request.signal.aborted) return;
      setConfig(data);
      onVenueRead(eventId, data.venue);
    } catch (failure) {
      if (request.signal.aborted) return;
      report(failure);
      if (confirmed || !(failure instanceof ApiError) || failure.status === 409 || failure.status >= 500) {
        setReviewRequired(true);
        if (!confirmed && (!(failure instanceof ApiError) || failure.status >= 500)) {
          setError('No se pudo confirmar el guardado. Consulta la configuración vigente antes de volver a guardar.');
        }
      }
    } finally {
      if (active.current === request) {
        active.current = null;
        setBusy(false);
      }
    }
  }

  const canEdit = !busy && !editor && !reviewRequired;
  const inactiveZones = new Set(config?.zones.filter(zone => !zone.active).map(zone => zone.id));
  return <div className="venue-page">
    {busy && <p role="status" className="venue-message">{config ? 'Guardando o consultando cambios…' : 'Consultando el recinto…'}</p>}
    {notice && <p role="status" className="venue-message">{notice}</p>}
    {error && <p role="alert" className="notice venue-message">{error}</p>}
    {(!config && error || reviewRequired) && <div>
      <button type="button" className="secondary" disabled={busy} onClick={() => void reload()}>
        {editor ? 'Consultar versión vigente y descartar borrador' : 'Consultar versión vigente'}
      </button>
    </div>}
    {config && <>
      {editor && <div className="panel" tabIndex={-1} ref={formHeading}>
        <VenueForm key={editor.entity + ('item' in editor ? editor.item?.id ?? '' : '')}
          editor={editor} config={config} disabled={busy || reviewRequired} onSave={change => void save(change)}
          onCancel={() => setEditor(null)} />
      </div>}
      <section className="panel" aria-labelledby="venue-info-title">
        <div className="venue-panel-heading">
          <h2 id="venue-info-title">Información del recinto</h2>
          <button type="button" className="secondary" disabled={!canEdit} onClick={() => setEditor({ entity: 'venue' })}>Editar recinto</button>
        </div>
        <dl className="venue-fields">
          <div><dt>Evento</dt><dd>{config.venue.name}</dd></div>
          <div><dt>Recinto</dt><dd>{config.venue.venue}</dd></div>
          <div><dt>Descripción operativa</dt><dd>{config.venue.description ?? 'Sin registrar'}</dd></div>
          <div><dt>Referencias de acceso</dt><dd>{config.venue.accessNotes ?? 'Sin registrar'}</dd></div>
          <div><dt>Referencias para responder a emergencias</dt><dd>{config.venue.responseNotes ?? 'Sin registrar'}</dd></div>
        </dl>
      </section>
      <section className="panel" aria-labelledby="venue-zones-title">
        <div className="venue-panel-heading">
          <h2 id="venue-zones-title">Zonas</h2>
          <button type="button" className="secondary" disabled={!canEdit} onClick={() => setEditor({ entity: 'zone' })}>Crear zona</button>
        </div>
        {config.zones.length ? <ul className="venue-list">{config.zones.map(zone => <li key={zone.id}>
          <div>
            <h3>{zone.name}</h3>
            <p>{zoneKinds[zone.kind]} · {zone.active ? 'Activa' : 'Inactiva'}</p>
            {zone.description && <p>{zone.description}</p>}
            {zone.reference && <p>Referencia · {zone.reference}</p>}
          </div>
          <button type="button" className="secondary" disabled={!canEdit} aria-label={`Editar zona ${zone.name}`}
            onClick={() => setEditor({ entity: 'zone', item: zone })}>Editar</button>
        </li>)}</ul> : <p>El evento aún no tiene zonas registradas.</p>}
        <p className="venue-help">Los asistentes pueden ver los nombres de las zonas activas. Las zonas inactivas salen de las opciones
          de nuevas solicitudes y conservan sus referencias históricas.</p>
      </section>
      <section className="panel" aria-labelledby="venue-points-title">
        <div className="venue-panel-heading">
          <h2 id="venue-points-title">Puntos operativos</h2>
          <button type="button" className="secondary" disabled={!canEdit || !config.zones.length}
            onClick={() => setEditor({ entity: 'point' })}>Crear punto</button>
        </div>
        {config.points.length ? <ul className="venue-list">{config.points.map(point => <li key={point.id}>
          <div>
            <h3>{point.name}</h3>
            <p>{pointKinds[point.kind]} · {point.zoneName} · {!point.active ? 'Inactivo' :
              inactiveZones.has(point.zoneId) ? 'Fuera de servicio por zona inactiva' : 'Activo'}</p>
            <p>Referencia · {point.reference}</p>
          </div>
          <button type="button" className="secondary" disabled={!canEdit} aria-label={`Editar punto ${point.name}`}
            onClick={() => setEditor({ entity: 'point', item: point })}>Editar</button>
        </li>)}</ul> : <p>{config.zones.length ? 'El evento aún no tiene puntos registrados.' : 'Crea una zona para asociarle puntos.'}</p>}
      </section>
      <section className="panel future-panel" aria-labelledby="venue-ai-title">
        <h2 id="venue-ai-title">Contexto para análisis asistido</h2>
        <p>El análisis puede usar referencias del recinto, zonas activas y sus puntos operativos para cotejar el relato.
          El servidor filtra y limita los textos. Usa referencias físicas y evita incluir datos personales.</p>
        <p>El guardia confirma la ubicación. Los puntos registrados son referencias operativas y su disponibilidad debe verificarse en terreno.</p>
      </section>
    </>}
  </div>;
}

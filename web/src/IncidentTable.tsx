import { useEffect, useState } from 'react';
import { ApiError, incidentDispatch, incidentLabels, incidentTriage, messageFor, priorityLabels, type Dispatch, type Incident, type Session } from './api.js';
import { alertLabels, categoryLabels, missingLabels, reasonLabels, triageLabels, uncertaintyLabels, urgencyLabels } from './triage.js';

const date = new Intl.DateTimeFormat('es-CL', { dateStyle: 'short', timeStyle: 'medium' });
const priorityIcons = { P0: '!', P1: '▲', P2: '◆', P3: '●', PENDING: '?' };
const sourceLabels: Record<string, string> = { HUMAN: 'Guardia', AI_PROVISIONAL: 'Provisional IA', DECLARED_DANGER: 'Peligro declarado' };

function displayLocation(dispatch: Dispatch) {
  for (const [source, location] of [
    ['Confirmada', dispatch.confirmedLocation], ['Declarada', dispatch.declaredLocation],
    ['Inferida por IA', dispatch.inferredLocation],
  ] as const) {
    if (location) return [source, location.zoneName, location.text].filter(Boolean).join(' · ');
  }
  return 'Ubicación desconocida';
}

export function IncidentTable({ items, session, onExpiry, actions }: {
  items: Incident[]; session: Session; onExpiry: () => void; actions?: (item: Incident) => React.ReactNode;
}) {
  if (!items.length) return <p className="empty">Este evento aún no tiene solicitudes registradas.</p>;
  return (
    <div className="table-scroll" tabIndex={0} role="region" aria-label="Solicitudes SOS del evento">
      <table>
        <thead><tr><th scope="col">Solicitante e ID</th><th scope="col">Ubicación</th>
          <th scope="col">Estado</th><th scope="col">Prioridad y triaje</th><th scope="col">Guardia asignado</th><th scope="col">Tiempos</th>
          {actions && <th scope="col">Acciones</th>}</tr></thead>
        <tbody>
          {items.map(item => (
            <tr key={item.id}>
              <th scope="row">{item.requesterName}<code>{item.id}</code></th>
              <td>{item.dispatch ? displayLocation(item.dispatch)
                : <>{item.zoneName ?? 'Ubicación desconocida'}{item.locationText && <small>{item.locationText}</small>}</>}</td>
              <td><span className={`status ${item.status.toLowerCase()}`}>{incidentLabels[item.status]}</span>
                {item.resolutionNote && <small>{item.resolutionNote}</small>}</td>
              <td>
                {item.dispatch && <DispatchCell item={item} dispatch={item.dispatch} session={session} onExpiry={onExpiry} />}
                {!item.hasConversation && <IncidentTriage item={item} session={session} onExpiry={onExpiry} />}
              </td>
              <td>{item.assignedStaff?.name ?? 'Sin asignar'}</td>
              <td>{([
                ['Creación', item.createdAt], ['Asignación', item.assignedAt], ['Inicio', item.startedAt],
                ['Resolución', item.resolvedAt], ['Último cambio', item.updatedAt],
              ] as const).map(([label, value]) => value && <small key={label}>
                {label}{' · '}<time dateTime={value}>{date.format(new Date(value))}</time>
              </small>)}</td>
              {actions && <td>{actions(item)}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function DispatchCell({ item, dispatch, session, onExpiry }: {
  item: Incident; dispatch: Dispatch; session: Session; onExpiry: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [audit, setAudit] = useState<Awaited<ReturnType<typeof incidentDispatch>> | null>(null);
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  const { id, eventId, version } = item;
  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    setError('');
    incidentDispatch(session, eventId, id, AbortSignal.any([controller.signal, AbortSignal.timeout(8000)])).then(data => {
      if (!controller.signal.aborted) setAudit(data);
    }).catch(failure => {
      if (controller.signal.aborted) return;
      if (failure instanceof ApiError && failure.status === 401) onExpiry();
      else setError(messageFor(failure));
    });
    return () => controller.abort();
  }, [open, session, eventId, id, version, dispatch.revision, retry, onExpiry]);
  const priority = dispatch.effectivePriority;
  const source = sourceLabels[dispatch.prioritySource];
  return <>
    <span className={`status priority-${priority.toLowerCase()}`}>
      <span aria-hidden="true">{priorityIcons[priority]} </span>{priorityLabels[priority]}{source && ` · ${source}`}
    </span>
    <small>{dispatch.priorityReasons.join(' · ')}</small>
    <small>{Math.floor(dispatch.elapsedSeconds / 60)} min {dispatch.elapsedSeconds % 60} s desde la emisión</small>
    <details className="triage-detail" onToggle={event => setOpen(event.currentTarget.open)}>
      <summary>Despacho y auditoría</summary>
      {dispatch.analysis && <>
        <small>Resumen sugerido por IA · {dispatch.analysis.summary}</small>
        {dispatch.analysis.uncertainty.length > 0 && <small>Por verificar · {dispatch.analysis.uncertainty.join(' · ')}</small>}
      </>}
      {error && <p role="alert">{error} <button className="secondary" onClick={() => setRetry(retry + 1)}>Reintentar</button></p>}
      {open && !audit && !error && <small role="status">Consultando auditoría…</small>}
      {audit?.transitions.map(entry => <small key={entry.version}>
        {entry.actorName} · {incidentLabels[entry.fromStatus]} → {incidentLabels[entry.toStatus]} · {date.format(new Date(entry.appliedAt))}
      </small>)}
      {audit?.reviews.map(entry => <small key={entry.revision}>
        {entry.actor.name} · {entry.humanPriority ? priorityLabels[entry.humanPriority] : 'Sin prioridad humana'} · {entry.reason}
        {' · '}{date.format(new Date(entry.reviewedAt))}
      </small>)}
    </details>
  </>;
}

function IncidentTriage({ item, session, onExpiry }: { item: Incident; session: Session; onExpiry: () => void }) {
  const [open, setOpen] = useState(false);
  const [detail, setDetail] = useState<Awaited<ReturnType<typeof incidentTriage>> | null>(null);
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  const version = item.triage?.triageVersion ?? 0;
  const { id, eventId } = item;
  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    setError('');
    setDetail(null);
    incidentTriage(session, eventId, id, controller.signal).then(data => {
      if (!controller.signal.aborted) setDetail(data);
    }).catch(failure => {
      if (controller.signal.aborted) return;
      if (failure instanceof ApiError && failure.status === 401) onExpiry();
      else setError(messageFor(failure));
    });
    return () => controller.abort();
  }, [open, session, eventId, id, version, retry, onExpiry]);
  const triage = detail && detail.triage.triageVersion >= version ? detail.triage : item.triage;
  const review = triage?.latestReview;
  const classification = review ?? triage?.suggestion ?? triage?.fallback;
  const result = triage?.suggestion ?? triage?.fallback;
  return (
    <>
      {item.dangerAlert && <strong className="status open">{alertLabels[item.dangerAlert]}</strong>}
      {item.reasonCode && <small>Motivo declarado · {reasonLabels[item.reasonCode]}</small>}
      {triage && <>
        <small>{review ? 'Revisión humana' : triageLabels[triage.status]}</small>
        {classification && <small>{categoryLabels[classification.category]} · {urgencyLabels[classification.urgency]}</small>}
        {review && <small><time dateTime={review.reviewedAt}>{date.format(new Date(review.reviewedAt))}</time></small>}
        <details className="triage-detail" onToggle={event => setOpen(event.currentTarget.open)}>
          <summary>Detalle e historial</summary>
          {result && <>
            <small>{triage.suggestion ? 'Resumen sugerido por IA' : 'Verificación manual'} · {result.summary}</small>
            <small>Incertidumbre · {uncertaintyLabels[result.uncertainty]}</small>
            {result.missingInformation.length > 0 && <small>Por verificar · {result.missingInformation.map(code => missingLabels[code]).join(', ')}</small>}
          </>}
          {error && <p role="alert">{error} <button className="secondary" onClick={() => setRetry(retry + 1)}>Reintentar</button></p>}
          {open && !detail && !error && <small role="status">Consultando historial…</small>}
          {detail?.reviews.length === 0 && <small>Sin revisiones registradas</small>}
          {detail?.reviews.map(entry => <small key={entry.reviewVersion}>
            {entry.actor.name} · <time dateTime={entry.reviewedAt}>{date.format(new Date(entry.reviewedAt))}</time>
            {' · '}{entry.decision === 'CONFIRM' ? 'Confirmó' : 'Corrigió'} · {categoryLabels[entry.category]} · {urgencyLabels[entry.urgency]}
          </small>)}
        </details>
      </>}
    </>
  );
}

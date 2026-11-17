import { activeIncident, incidentLabels, priorityLabels, type Incident } from './api.js';
import { incidentLocation } from './IncidentTable.js';
import { historyHref, queueHref } from './organizerNavigation.js';

function Indicator({ label, count }: { label: string; count: number }) {
  return <div className="indicator"><dt>{label}</dt><dd>{count}</dd></div>;
}

export function DashboardOverview({ items }: { items: Incident[] }) {
  const active = items.filter(activeIncident);
  const counts = { OPEN: 0, ASSIGNED: 0, IN_PROGRESS: 0 };
  for (const { status } of active) counts[status]++;

  return (
    <div className="dashboard">
      <section className="overview-panel" aria-labelledby="operation-title">
        <h2 id="operation-title">Operación actual</h2>
        <dl className="indicator-grid">
          <Indicator label="Activas" count={active.length} />
          <Indicator label="Sin asignar" count={counts.OPEN} />
          <Indicator label="Asignadas" count={counts.ASSIGNED} />
          <Indicator label="En atención" count={counts.IN_PROGRESS} />
        </dl>
      </section>
      <div className="dashboard-panels">
        <section className="panel queue-preview" aria-labelledby="preview-title">
          <h2 id="preview-title">Primeras en la cola</h2>
          {active.length === 0 ? <p className="panel-empty">No hay solicitudes activas.</p> :
            <ul>{active.slice(0, 5).map(item => {
              const priority = item.dispatch?.effectivePriority;
              return <li key={item.id}>
                <div>
                  <code>{item.id}</code>
                  <span className="queue-location">{incidentLocation(item)}</span>
                </div>
                <div className="queue-status">
                  <span className={`status ${item.status.toLowerCase()}`}>{incidentLabels[item.status]}</span>
                  <span className={`status${priority ? ' priority-' + priority.toLowerCase() : ''}`}>
                    {priority ? priorityLabels[priority] : 'Prioridad no disponible'}
                  </span>
                </div>
              </li>;
            })}</ul>}
        </section>
        <section className="panel quick-access" aria-labelledby="quick-access-title">
          <h2 id="quick-access-title">Accesos rápidos</h2>
          <a href={queueHref}>Ver cola activa <span aria-hidden="true">→</span></a>
          <a href={historyHref}>Ver historial <span aria-hidden="true">→</span></a>
        </section>
      </div>
    </div>
  );
}

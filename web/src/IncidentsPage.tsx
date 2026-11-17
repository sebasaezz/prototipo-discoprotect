import type { ReactNode } from 'react';
import { activeIncident, type Incident, type Session } from './api.js';
import { IncidentTable } from './IncidentTable.js';

export function IncidentsPage({ items, session, onExpiry, onAccessLost, rowActions, purgeEnabled, children }: {
  items: Incident[];
  session: Session;
  onExpiry: () => void;
  onAccessLost: () => void;
  rowActions: (item: Incident) => ReactNode;
  purgeEnabled: boolean;
  children: ReactNode;
}) {
  const active = items.filter(activeIncident);
  const history = items.filter(item => !activeIncident(item));

  return <div className="incident-panels">
    {children}
    <section className="panel incident-panel" aria-labelledby="cola-activa">
      <h2 id="cola-activa" tabIndex={-1}>Cola activa</h2>
      {active.length > 0
        ? <IncidentTable items={active} session={session} onExpiry={onExpiry} onAccessLost={onAccessLost}
            actions={rowActions} label="Cola activa" />
        : <p className="panel-empty">No hay solicitudes activas.</p>}
    </section>
    <section className="panel incident-panel" aria-labelledby="historial">
      <h2 id="historial" tabIndex={-1}>Historial</h2>
      {history.length > 0
        ? <IncidentTable items={history} session={session} onExpiry={onExpiry} onAccessLost={onAccessLost}
            actions={purgeEnabled ? rowActions : undefined} label="Historial de incidentes" />
        : <p className="panel-empty">Todavía no hay solicitudes en el historial.</p>}
    </section>
  </div>;
}

import { incidentLabels, type Incident } from './api.js';

const date = new Intl.DateTimeFormat('es-CL', { dateStyle: 'short', timeStyle: 'medium' });

export function IncidentTable({ items }: { items: Incident[] }) {
  if (!items.length) return <p className="empty">Este evento aún no tiene solicitudes registradas.</p>;
  return (
    <div className="table-scroll" tabIndex={0} role="region" aria-label="Solicitudes SOS del evento">
      <table>
        <thead><tr><th scope="col">Solicitante e ID</th><th scope="col">Ubicación</th>
          <th scope="col">Estado</th><th scope="col">Guardia asignado</th><th scope="col">Tiempos</th></tr></thead>
        <tbody>
          {items.map(item => (
            <tr key={item.id}>
              <th scope="row">{item.requesterName}<code>{item.id}</code></th>
              <td>{item.zoneName ?? 'Ubicación desconocida'}{item.locationText && <small>{item.locationText}</small>}</td>
              <td><span className={`status ${item.status.toLowerCase()}`}>{incidentLabels[item.status]}</span>
                {item.resolutionNote && <small>{item.resolutionNote}</small>}</td>
              <td>{item.assignedStaff?.name ?? 'Sin asignar'}</td>
              <td>{([
                ['Creación', item.createdAt], ['Asignación', item.assignedAt], ['Inicio', item.startedAt],
                ['Resolución', item.resolvedAt], ['Último cambio', item.updatedAt],
              ] as const).map(([label, value]) => value && <small key={label}>
                {label}{' · '}<time dateTime={value}>{date.format(new Date(value))}</time>
              </small>)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

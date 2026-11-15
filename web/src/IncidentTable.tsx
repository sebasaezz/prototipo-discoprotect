import type { Incident } from './api.js';

const date = new Intl.DateTimeFormat('es-CL', { dateStyle: 'short', timeStyle: 'short' });

export function IncidentTable({ items }: { items: Incident[] }) {
  if (!items.length) return <p className="empty">Este evento aún no tiene solicitudes registradas.</p>;
  return (
    <div className="table-scroll" tabIndex={0} role="region" aria-label="Solicitudes SOS del evento">
      <table>
        <thead><tr><th scope="col">Solicitante e ID</th><th scope="col">Ubicación</th>
          <th scope="col">Creación</th><th scope="col">Estado</th></tr></thead>
        <tbody>
          {items.map(item => (
            <tr key={item.id}>
              <th scope="row">{item.requesterName}<code>{item.id}</code></th>
              <td>{item.zoneName ?? 'Ubicación desconocida'}{item.locationText && <small>{item.locationText}</small>}</td>
              <td><time dateTime={item.createdAt}>{date.format(new Date(item.createdAt))}</time></td>
              <td><span className={`status ${item.status === 'OPEN' ? 'open' : 'cancelled'}`}>
                {item.status === 'OPEN' ? 'Abierta' : 'Cancelada'}
              </span></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

import { useEffect, useState, type FormEvent } from 'react';
import { ApiError, incidentLabels, messageFor, priorityLabels, request, type Session } from './api.js';
import { categoryLabels, reasonLabels } from './triage.js';

type Distribution = Record<string, number>;
type Duration = { count: number; meanSeconds: number; medianSeconds: number; p90Seconds: number };
type Statistics = {
  timeZone: string; zones: { id: string; name: string }[]; total: number; active: number; resolved: number;
  byStatus: Distribution; byPriority: Distribution; byZone: Distribution; byReason: Distribution; byCategory: Distribution; daily: Distribution;
  durations: Record<'assigned' | 'started' | 'closed', Duration | null>;
};

function DistributionChart({ title, values, labels }: { title: string; values: Distribution; labels?: Record<string, string> }) {
  const rows = Object.entries(values);
  const largest = Math.max(1, ...rows.map(([, count]) => count));
  return <section className="panel"><h2>{title}</h2>
    {rows.length === 0 ? <p>Sin solicitudes.</p> : <ul className="distribution">
      {rows.map(([key, count]) => <li key={key}>
        <span>{labels?.[key] ?? key}</span><strong>{count}</strong>
        <meter aria-hidden="true" min={0} max={largest} value={count} />
      </li>)}
    </ul>}
  </section>;
}

const durationLabels = { assigned: 'Asignación', started: 'Inicio de atención', closed: 'Resolución o cierre' } as const;
const format = new Intl.NumberFormat('es-CL', { maximumFractionDigits: 1 });

export function StatisticsPage({ eventId, session, onExpiry }: { eventId: string; session: Session; onExpiry: () => void }) {
  const [filter, setFilter] = useState('');
  const [revision, setRevision] = useState(0);
  const [data, setData] = useState<Statistics>();
  const [zones, setZones] = useState<Statistics['zones']>([]);
  const [error, setError] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    request<Statistics>(`events/${encodeURIComponent(eventId)}/statistics?${filter}`,
      AbortSignal.any([controller.signal, AbortSignal.timeout(8000)]), session).then(result => {
      if (controller.signal.aborted) return;
      setZones(result.zones);
      setData(result);
    }).catch(failure => {
      if (controller.signal.aborted) return;
      if (failure instanceof ApiError && failure.status === 401) onExpiry();
      else setError(messageFor(failure));
    });
    return () => controller.abort();
  }, [eventId, filter, onExpiry, revision, session]);

  function load(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const parameters = new URLSearchParams();
    for (const [key, value] of new FormData(event.currentTarget)) {
      if (typeof value === 'string' && value) parameters.append(key, value);
    }
    setData(undefined);
    setError('');
    setFilter(parameters.toString());
    setRevision(value => value + 1);
  }

  return <>
    <form className="panel statistics-filters" onSubmit={load}>
      <label>Desde<input type="date" name="from" /></label>
      <label>Hasta<input type="date" name="through" /></label>
      <label>Estado<select name="status"><option value="">Todos</option>
        {Object.entries(incidentLabels).map(([key, name]) => <option key={key} value={key}>{name}</option>)}
      </select></label>
      <label>Prioridad<select name="priority"><option value="">Todas</option>
        {Object.entries(priorityLabels).map(([key, name]) => <option key={key} value={key}>{name}</option>)}
      </select></label>
      <label>Zona<select name="zoneId"><option value="">Todas</option>
        {zones.map(({ id, name }) => <option key={id} value={id}>{name}</option>)}
      </select></label>
      <button>Consultar estadísticas</button>
    </form>
    {error ? <p role="alert" className="notice">{error}</p> : !data ? <p role="status">Consultando estadísticas…</p> : <>
      <section className="panel">
        <p><strong>{data.total}</strong> solicitudes · <strong>{data.active}</strong> activas · <strong>{data.resolved}</strong> resueltas</p>
        <p>Fechas de creación en hora local de Chile continental ({data.timeZone}).</p>
      </section>
      <div className="statistics-grid">
        <DistributionChart title="Solicitudes por estado" values={data.byStatus} labels={incidentLabels} />
        <DistributionChart title="Solicitudes por prioridad efectiva" values={data.byPriority} labels={priorityLabels} />
        <DistributionChart title="Solicitudes por zona" values={data.byZone}
          labels={Object.fromEntries([['', 'Sin zona'], ...data.zones.map(({ id, name }) => [id, name])])} />
        <DistributionChart title="Solicitudes por motivo" values={data.byReason} labels={{ ...reasonLabels, '': 'Sin motivo codificado' }} />
        <DistributionChart title="Solicitudes por categoría" values={data.byCategory} labels={{ ...categoryLabels, '': 'Sin clasificación' }} />
        <DistributionChart title="Solicitudes recibidas por día" values={data.daily} />
        <section className="panel"><h2>Tiempos desde la creación</h2>
          <dl>{Object.entries(durationLabels).map(([key, label]) => {
            const duration = data.durations[key as keyof Statistics['durations']];
            return <div key={key}><dt>{label}</dt><dd>{duration
              ? `${format.format(duration.meanSeconds)} s promedio · ${format.format(duration.medianSeconds)} s mediana · ${format.format(duration.p90Seconds)} s p90 (${duration.count} registros)`
              : 'Sin marcas temporales disponibles'}</dd></div>;
          })}</dl>
        </section>
      </div>
    </>}
  </>;
}

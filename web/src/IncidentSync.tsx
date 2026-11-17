export function IncidentSync({ lastRead, error, refresh }: {
  lastRead: Date | null;
  error: string;
  refresh: () => void;
}) {
  return (
    <>
      <div className="incident-sync">
        {lastRead && <p>Última consulta · <time dateTime={lastRead.toISOString()}>
          {lastRead.toLocaleTimeString('es-CL')}
        </time></p>}
        {!lastRead && !error && <p role="status">Consultando incidentes…</p>}
        <button type="button" className="secondary" onClick={refresh}>Actualizar incidentes</button>
      </div>
      {error && <p className="notice" role="alert">
        {error}{lastRead && ' Se muestra la última consulta disponible.'}
      </p>}
    </>
  );
}

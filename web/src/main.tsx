import { StrictMode, useCallback, useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { createRoot } from 'react-dom/client';
import { ApiError, currentIncidentSnapshot, events, incidents, login, messageFor } from './api.js';
import type { Event, Incident, Session } from './api.js';
import { IncidentTable } from './IncidentTable.js';
import { incidentPolling } from './incidentPolling.js';
import './styles.css';

function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [message, setMessage] = useState('');

  const expire = useCallback(() => {
    setMessage('La sesión venció. Vuelve a iniciar sesión.');
    setSession(null);
  }, []);

  useEffect(() => {
    if (!session) return;
    const timeout = window.setTimeout(expire, Math.max(0, Date.parse(session.expiresAt) - Date.now()));
    return () => window.clearTimeout(timeout);
  }, [session, expire]);

  function signOut(reason = '') {
    setMessage(reason);
    setSession(null);
  }

  return (
    <>
      <header className="masthead">
        <div className="frame masthead-content">
          <div><strong>Discoprotect</strong><span>Organización</span></div>
          {session && <button className="secondary header-button" onClick={() => signOut()}>Cerrar sesión</button>}
        </div>
      </header>
      <main className="frame">
        {session
          ? <Dashboard session={session} onExpiry={expire} />
          : <SignIn onLogin={value => { setMessage(''); setSession(value); }} message={message} />}
      </main>
    </>
  );
}

function SignIn({ onLogin, message }: { onLogin: (session: Session) => void; message: string }) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const active = useRef<AbortController | null>(null);
  useEffect(() => () => active.current?.abort(), []);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (active.current) return;
    const form = event.currentTarget;
    const pin = new FormData(form).get('pin');
    if (typeof pin !== 'string' || pin.length < 4 || pin.length > 128) return;
    form.reset();
    const request = new AbortController();
    active.current = request;
    setLoading(true);
    setError('');
    try {
      const result = await login(pin, request.signal);
      if (!request.signal.aborted) onLogin(result);
    } catch (failure) {
      if (!request.signal.aborted) setError(messageFor(failure));
    } finally {
      active.current = null;
      if (!request.signal.aborted) setLoading(false);
    }
  }

  return (
    <section className="login-panel">
      <h1>Incidentes del evento</h1>
      <p>Inicia sesión con el PIN de la cuenta de demostración <strong>organizador</strong>.</p>
      <form onSubmit={submit}>
        <label htmlFor="pin">PIN de organizador</label>
        <input id="pin" name="pin" type="password" autoComplete="off" minLength={4} maxLength={128}
          required disabled={loading} />
        <button type="submit" disabled={loading}>{loading ? 'Validando sesión…' : 'Iniciar sesión'}</button>
      </form>
      {(error || message) && <p className="notice" role="alert">{error || message}</p>}
    </section>
  );
}

function Dashboard({ session, onExpiry }: { session: Session; onExpiry: () => void }) {
  const [allowed, setAllowed] = useState<Event[] | null>(null);
  const [selected, setSelected] = useState('');
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    const request = new AbortController();
    events(session, request.signal).then(result => {
      if (request.signal.aborted) return;
      setAllowed(result);
      setSelected(result.length === 1 ? result[0].id : '');
    }).catch(failure => {
      if (request.signal.aborted) return;
      if (failure instanceof ApiError && failure.status === 401) onExpiry();
      else setError(messageFor(failure));
    });
    return () => request.abort();
  }, [session, revision, onExpiry]);

  return (
    <>
      <h1>Incidentes del evento</h1>
      {error && <div className="notice" role="alert">{error} <button className="secondary"
        onClick={() => { setError(''); setRevision(revision + 1); }}>Volver a consultar</button></div>}
      {!error && allowed === null && <p role="status">Consultando eventos…</p>}
      {allowed?.length === 0 && <p>La cuenta no tiene eventos autorizados.</p>}
      {!!allowed?.length && (
        <div className="event-selector">
          <label htmlFor="event">Evento autorizado</label>
          <select id="event" value={selected} onChange={event => setSelected(event.target.value)}>
            <option value="" disabled>Selecciona un evento</option>
            {allowed.map(event => <option key={event.id} value={event.id}>{event.name} · {event.venue}</option>)}
          </select>
        </div>
      )}
      {selected && <IncidentList key={selected} session={session} event={selected} onExpiry={onExpiry} />}
    </>
  );
}

function IncidentList({ session, event, onExpiry }: { session: Session; event: string; onExpiry: () => void }) {
  const [items, setItems] = useState<Incident[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [lastRead, setLastRead] = useState<Date | null>(null);
  const refresh = useRef<() => void>(() => {});

  useEffect(() => {
    const polling = incidentPolling(signal => {
      if (Date.parse(session.expiresAt) <= Date.now()) throw new ApiError(401, 'La sesión venció.');
      return incidents(session, event, signal);
    }, result => {
      setItems(current => currentIncidentSnapshot(current, result));
      setLastRead(new Date());
      setError('');
      setLoading(false);
    }, failure => {
      if (failure instanceof ApiError && failure.status === 401) {
        onExpiry();
        return false;
      }
      if (failure instanceof ApiError && failure.status === 403) {
        setItems(null);
        setLastRead(null);
      }
      setError(messageFor(failure));
      setLoading(false);
      return true;
    });
    refresh.current = polling.refresh;
    const visibility = () => polling.setVisible(document.visibilityState === 'visible');
    document.addEventListener('visibilitychange', visibility);
    visibility();
    return () => {
      document.removeEventListener('visibilitychange', visibility);
      polling.setVisible(false);
    };
  }, [session, event, onExpiry]);

  return (
    <section aria-labelledby="sos-title">
      <div className="list-header">
        <h2 id="sos-title">Solicitudes SOS</h2>
        <button className="secondary" onClick={() => refresh.current()}>Actualizar incidentes</button>
      </div>
      {loading && <p role="status">Consultando incidentes…</p>}
      {error && <p role="alert" className="notice">{error} {items && 'Se muestra la última consulta disponible.'}</p>}
      {lastRead && <p>Última consulta · <time dateTime={lastRead.toISOString()}>{lastRead.toLocaleTimeString('es-CL')}</time></p>}
      {items && <IncidentTable items={items} session={session} onExpiry={onExpiry} />}
    </section>
  );
}

const root = document.getElementById('root');
if (root) createRoot(root).render(<StrictMode><App /></StrictMode>);

import { StrictMode, useCallback, useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { createRoot } from 'react-dom/client';
import { login, messageFor, type Session } from './api.js';
import { OrganizerWorkspace } from './OrganizerWorkspace.js';
import { resetSection } from './organizerNavigation.js';
import './styles.css';

function App() {
  const [{ session, message }, setAuth] = useState<{ session: Session | null; message: string }>({
    session: null, message: '',
  });
  const expire = useCallback(() => {
    setAuth(current => current.session === session
      ? { session: null, message: 'La sesión venció. Vuelve a iniciar sesión.' }
      : current);
  }, [session]);

  useEffect(() => {
    if (!session) return;
    const timeout = window.setTimeout(expire, Math.max(0, Date.parse(session.expiresAt) - Date.now()));
    return () => window.clearTimeout(timeout);
  }, [session, expire]);

  if (session) return <OrganizerWorkspace session={session} onExpiry={expire}
    onSignOut={() => setAuth({ session: null, message: '' })} />;

  return (
    <>
      <header className="masthead">
        <div className="frame masthead-content">
          <div><strong>Discoprotect</strong><span>Organización</span></div>
        </div>
      </header>
      <main className="frame">
        <SignIn onLogin={value => {
          resetSection();
          setAuth({ session: value, message: '' });
        }} message={message} />
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
      <h1>GestiónWeb</h1>
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

const root = document.getElementById('root');
if (root) createRoot(root).render(<StrictMode><App /></StrictMode>);

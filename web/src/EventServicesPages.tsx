import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, eventStatusLabels, incidentLabels, messageFor, request, type Event, type Session } from './api.js';

type PageProps = { eventId: string; session: Session; onExpiry: () => void };
type Ticket = { id: string; name: string; ticketId: string | null; usedAt: string | null; revokedAt: string | null; expiresAt: string | null };
type Promotion = { id: string; title: string; description: string; imageUrl: string | null; linkUrl: string | null;
  startsAt: string; endsAt: string; active: boolean; version: number };

function useEventService({ eventId, session, onExpiry }: PageProps) {
  const [error, setError] = useState('');
  const pending = useRef(new Set<AbortController>());
  const call = useCallback(async <T,>(path: string, command?: { method: 'POST' | 'PATCH'; body: unknown }) => {
    const controller = new AbortController();
    pending.current.add(controller);
    setError('');
    try {
      return await request<T>(`events/${encodeURIComponent(eventId)}/${path}`,
        AbortSignal.any([controller.signal, AbortSignal.timeout(8000)]), session, command);
    } catch (failure) {
      if (!controller.signal.aborted) {
        if (failure instanceof ApiError && failure.status === 401) onExpiry();
        else setError(messageFor(failure));
      }
      return undefined;
    } finally {
      pending.current.delete(controller);
    }
  }, [eventId, onExpiry, session]);
  useEffect(() => {
    const requests = pending.current;
    return () => { for (const controller of requests) controller.abort(); };
  }, [call]);
  return { call, error };
}

const dateLabel = (value: string) => new Intl.DateTimeFormat('es-CL', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
function localInput(value?: string) {
  if (!value) return '';
  const date = new Date(value);
  return new Date(date.valueOf() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}
const browserZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
type Schedule = { startsAt: string; endsAt: string; status: Event['status']; version: number };

export function EventAdministrationPage({ event, session, onExpiry, onSaved }: {
  event?: Event; session: Session; onExpiry: () => void; onSaved: (event: Event) => void;
}) {
  const [schedule, setSchedule] = useState<Schedule>();
  const [mode, setMode] = useState<'create' | 'edit'>(event ? 'edit' : 'create');
  const [revision, setRevision] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const pending = useRef<AbortController | null>(null);
  const creationId = useRef<string | null>(null);
  const path = event ? `events/${encodeURIComponent(event.id)}/schedule` : '';
  useEffect(() => {
    if (!path) return;
    const controller = new AbortController();
    request<Schedule>(path, AbortSignal.any([controller.signal, AbortSignal.timeout(8000)]), session).then(data => {
      if (!controller.signal.aborted) setSchedule(data);
    }).catch(failure => {
      if (controller.signal.aborted) return;
      if (failure instanceof ApiError && failure.status === 401) onExpiry();
      else setError(messageFor(failure));
    });
    return () => controller.abort();
  }, [path, session, onExpiry, revision]);
  useEffect(() => () => pending.current?.abort(), []);
  const editing = mode === 'edit';
  async function submit(submission: FormEvent<HTMLFormElement>) {
    submission.preventDefault();
    if (busy || editing && !schedule) return;
    const form = new FormData(submission.currentTarget);
    const get = (name: string) => String(form.get(name) ?? '').trim();
    if (editing && get('status') !== schedule?.status && !window.confirm('¿Cambiar el estado operativo del evento?')) return;
    const details = { startsAt: new Date(get('starts')).toISOString(), endsAt: new Date(get('ends')).toISOString(), status: get('status') };
    const controller = new AbortController();
    pending.current = controller;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const updated = await request<Event>(editing ? path : 'events',
        AbortSignal.any([controller.signal, AbortSignal.timeout(8000)]), session, {
          method: editing ? 'PATCH' : 'POST',
          body: editing ? { ...details, version: schedule?.version } :
            { ...details, id: creationId.current ??= crypto.randomUUID(), name: get('name'), venue: get('venue'),
              demoAttendees: form.has('demoAttendees') },
        });
      if (controller.signal.aborted) return;
      creationId.current = null;
      setNotice(editing ? 'Configuración guardada.' : 'Evento creado.');
      setRevision(value => value + 1);
      setMode('edit');
      onSaved(updated);
    } catch (failure) {
      if (controller.signal.aborted) return;
      if (failure instanceof ApiError && failure.status === 401) onExpiry();
      else setError(messageFor(failure));
    } finally {
      if (!controller.signal.aborted) setBusy(false);
    }
  }
  const fields = editing ? schedule : undefined;
  return <section className="panel">
    <h2>{editing ? 'Horario y estado' : 'Crear evento de demostración'}</h2>
    {event && <button className="secondary" disabled={busy} onClick={() => {
      setMode(editing ? 'create' : 'edit');
      setError('');
      setNotice('');
    }}>{editing ? 'Crear otro evento' : 'Volver al evento seleccionado'}</button>}
    {editing && <button className="secondary" disabled={busy} onClick={() => {
      setSchedule(undefined); setError(''); setRevision(value => value + 1);
    }}>Consultar versión vigente</button>}
    {error && <p role="alert" className="notice">{error}</p>}
    {notice && <p role="status">{notice}</p>}
    {editing && !fields && !error && <p role="status">Consultando configuración…</p>}
    {(!editing || fields) && <form className="statistics-filters" key={`${mode}-${fields?.version}`} onSubmit={submit}>
      {!editing && <>
        <label>Nombre<input name="name" maxLength={160} required /></label>
        <label>Recinto<input name="venue" maxLength={160} required /></label>
        <label><input type="checkbox" name="demoAttendees" /> Autorizar las tres cuentas ficticias de asistentes</label>
      </>}
      <label>Inicio ({browserZone})<input type="datetime-local" name="starts" required defaultValue={localInput(fields?.startsAt)} /></label>
      <label>Fin ({browserZone})<input type="datetime-local" name="ends" required defaultValue={localInput(fields?.endsAt)} /></label>
      <label>Estado<select name="status" defaultValue={fields?.status ?? 'PLANNED'}>
        {Object.entries(eventStatusLabels).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
      </select></label>
      <button disabled={busy}>{editing ? 'Guardar configuración' : 'Crear evento'}</button>
    </form>}
    {editing && <p>El nombre, las zonas y los puntos operativos se editan en <a href="#recinto">Recinto y zonas</a>.</p>}
  </section>;
}

const validationLabels = { VALID: 'Entrada válida. Ingreso registrado.', USED: 'Entrada ya utilizada.',
  REVOKED: 'Entrada revocada.', EXPIRED: 'Entrada vencida.', OTHER_EVENT: 'Entrada de otro evento.', INVALID: 'Código inválido.' } as const;

function Pagination({ offset, count, onPage }: { offset: number; count: number; onPage: (value: number) => void }) {
  return <div>
    <button className="secondary" disabled={!offset} onClick={() => onPage(offset - 25)}>Anterior</button>{' '}
    <button className="secondary" disabled={count < 25} onClick={() => onPage(offset + 25)}>Siguiente</button>
  </div>;
}

export function TicketsPage(props: PageProps) {
  const { call, error } = useEventService(props);
  const [entries, setEntries] = useState<Ticket[]>();
  const [offset, setOffset] = useState(0);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState('');
  const check = useRef<{ code: string; operationId: string } | undefined>(undefined);
  const path = `tickets?offset=${offset}`;
  const load = useCallback(async () => {
    const data = await call<{ attendees: Ticket[] }>(path);
    if (data) setEntries(data.attendees);
  }, [call, path]);
  useEffect(() => { void load(); }, [load]);

  async function issue(event: FormEvent<HTMLFormElement>, attendeeId: string) {
    event.preventDefault();
    setBusy(true);
    const expiresAt = new Date(String(new FormData(event.currentTarget).get('expiresAt'))).toISOString();
    if (await call('tickets', { method: 'POST', body: { attendeeId, expiresAt } })) await load();
    setBusy(false);
  }
  async function revoke(ticketId: string) {
    if (!window.confirm('¿Revocar esta entrada? El código dejará de ser válido.')) return;
    setBusy(true);
    if (await call(`tickets/${ticketId}/revoke`, { method: 'POST', body: {} })) await load();
    setBusy(false);
  }
  async function validate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const code = String(new FormData(event.currentTarget).get('code')).trim();
    if (check.current?.code !== code) check.current = { code, operationId: crypto.randomUUID() };
    setBusy(true);
    setResult('');
    const response = await call<{ result: keyof typeof validationLabels }>('tickets/validate', { method: 'POST', body: check.current });
    if (response) {
      check.current = undefined;
      setResult(validationLabels[response.result]);
      await load();
    }
    setBusy(false);
  }
  return <>
    {error && <p role="alert" className="notice">{error}</p>}
    <section className="panel"><h2>Control de ingreso</h2>
      <form className="statistics-filters" onSubmit={validate}>
        <label>Código de entrada<input name="code" required maxLength={120} autoComplete="off" /></label>
        <button disabled={busy}>Validar ingreso</button>
      </form>
      <p role="status">{result}</p>
    </section>
    <section className="panel"><h2>Entradas ficticias</h2>
      <p>Vencimiento en la zona horaria del navegador ({browserZone}).</p>
      {!entries && !error && <p role="status">Consultando entradas…</p>}
      {entries?.length === 0 && <p>Sin asistentes en esta página.</p>}
      <ul>{entries?.map(ticket => <li key={ticket.id}>
        <strong>{ticket.name}</strong>
        {ticket.ticketId ? <>
          <p>{ticket.revokedAt ? 'Revocada' : ticket.usedAt ? 'Utilizada' : 'Emitida'} · vence {dateLabel(ticket.expiresAt!)}</p>
          {!ticket.revokedAt && <button className="secondary" disabled={busy} onClick={() => void revoke(ticket.ticketId!)}>Revocar entrada</button>}
        </> : <form className="statistics-filters" onSubmit={event => void issue(event, ticket.id)}>
          <label>Vencimiento<input type="datetime-local" name="expiresAt" required /></label>
          <button disabled={busy}>Emitir entrada</button>
        </form>}
      </li>)}</ul>
      <Pagination offset={offset} count={entries?.length ?? 0} onPage={setOffset} />
    </section>
  </>;
}

export function PromotionsPage(props: PageProps) {
  const { call, error } = useEventService(props);
  const [items, setItems] = useState<Promotion[]>();
  const [offset, setOffset] = useState(0);
  const [editing, setEditing] = useState<Promotion | 'new'>();
  const [busy, setBusy] = useState(false);
  const path = `promotions?offset=${offset}`;
  const load = useCallback(async () => {
    const data = await call<{ promotions: Promotion[] }>(path);
    if (data) setItems(data.promotions);
  }, [call, path]);
  useEffect(() => { void load(); }, [load]);
  const current = editing === 'new' ? undefined : editing;

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    const form = new FormData(event.currentTarget);
    const value = (key: string) => String(form.get(key) ?? '').trim();
    const body = { title: value('title'), description: value('description'), imageUrl: value('imageUrl') || null,
      linkUrl: value('linkUrl') || null, startsAt: new Date(value('startsAt')).toISOString(),
      endsAt: new Date(value('endsAt')).toISOString(), active: form.has('active'),
      ...(current ? { version: current.version } : {}) };
    if (await call(current ? `promotions/${current.id}` : 'promotions', { method: current ? 'PATCH' : 'POST', body })) {
      setEditing(undefined);
      await load();
    }
    setBusy(false);
  }
  return <section className="panel"><h2>Campañas del evento</h2>
    {error && <p className="notice" role="alert">{error}</p>}
    <button disabled={busy} onClick={() => setEditing('new')}>Crear promoción</button>{' '}
    <button className="secondary" disabled={busy} onClick={() => { setEditing(undefined); void load(); }}>Consultar versión vigente</button>
    {!items && !error && <p role="status">Consultando promociones…</p>}
    {items?.length === 0 && <p>Sin promociones en esta página.</p>}
    <ul>{items?.map(item => <li key={item.id}>
      <strong>{item.title}</strong> · {item.active ? 'Habilitada' : 'Deshabilitada'}
      <p>{item.description}</p>
      <p>{dateLabel(item.startsAt)} – {dateLabel(item.endsAt)}</p>
      <button className="secondary" disabled={busy} onClick={() => setEditing(item)}>Editar {item.title}</button>
    </li>)}</ul>
    <Pagination offset={offset} count={items?.length ?? 0} onPage={setOffset} />
    {editing && <form key={current?.id ?? 'new'} className="statistics-filters" onSubmit={save}>
      <label>Título<input name="title" required maxLength={100} defaultValue={current?.title} /></label>
      <label>Descripción<textarea name="description" required maxLength={1000} defaultValue={current?.description} /></label>
      <label>Imagen HTTPS<input name="imageUrl" type="url" maxLength={500} defaultValue={current?.imageUrl ?? ''} /></label>
      <label>Enlace HTTPS<input name="linkUrl" type="url" maxLength={500} defaultValue={current?.linkUrl ?? ''} /></label>
      <label>Inicio ({browserZone})<input name="startsAt" type="datetime-local" required defaultValue={localInput(current?.startsAt)} /></label>
      <label>Fin ({browserZone})<input name="endsAt" type="datetime-local" required defaultValue={localInput(current?.endsAt)} /></label>
      <label><input name="active" type="checkbox" defaultChecked={current?.active} /> Habilitada</label>
      <button disabled={busy}>Guardar promoción</button>
      <button type="button" className="secondary" disabled={busy} onClick={() => setEditing(undefined)}>Cancelar</button>
    </form>}
  </section>;
}

type SearchResult = { id: string; name?: string; status?: keyof typeof incidentLabels; expiresAt?: string };
export function SearchPage(props: PageProps) {
  const { call, error } = useEventService(props);
  const [type, setType] = useState('incidents');
  const [results, setResults] = useState<SearchResult[]>();
  const [query, setQuery] = useState('');
  const [offset, setOffset] = useState(0);
  const [busy, setBusy] = useState(false);
  async function read(parameters: string, page: number) {
    setBusy(true);
    setResults(undefined);
    const data = await call<{ results: SearchResult[] }>(`search?${parameters}&offset=${page}`);
    if (data) {
      setResults(data.results);
      setOffset(page);
    }
    setBusy(false);
  }
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const parameters = new URLSearchParams();
    for (const [key, value] of new FormData(event.currentTarget)) {
      if (typeof value === 'string' && value.trim()) parameters.set(key, value.trim());
    }
    setQuery(parameters.toString());
    void read(parameters.toString(), 0);
  }
  return <section className="panel"><h2>Buscar en el evento</h2>
    <form className="statistics-filters" onSubmit={submit}>
      <label>Tipo<select name="type" value={type} onChange={event => setType(event.target.value)}>
        <option value="incidents">Incidentes</option><option value="zones">Zonas</option>
        <option value="staff">Guardias autorizados</option><option value="tickets">Entradas</option>
      </select></label>
      {type === 'incidents' || type === 'tickets'
        ? <label>ID<input key={type} name="id" maxLength={36} required={type === 'tickets'} /></label>
        : <label>Nombre<input key={type} name="text" maxLength={80} /></label>}
      {type === 'incidents' && <label>Estado<select name="status"><option value="">Todos</option>
        {Object.entries(incidentLabels).map(([status, label]) => <option key={status} value={status}>{label}</option>)}
      </select></label>}
      <button disabled={busy}>Buscar</button>
    </form>
    {busy && <p role="status">Buscando…</p>}
    {error && <p role="alert" className="notice">{error}</p>}
    {results && (results.length ? <ul>{results.map(item => <li key={item.id}>
      {item.name ?? item.id}{item.status && ` · ${incidentLabels[item.status]}`}{item.expiresAt && ` · vence ${dateLabel(item.expiresAt)}`}
    </li>)}</ul> : <p>Sin coincidencias.</p>)}
    {results && <Pagination offset={offset} count={results.length} onPage={page => void read(query, page)} />}
  </section>;
}

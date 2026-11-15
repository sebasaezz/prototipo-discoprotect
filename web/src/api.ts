export type Session = { token: string; expiresAt: string };
export type Event = { id: string; name: string; venue: string; role: string };
export type Incident = {
  id: string;
  eventId: string;
  requesterName: string;
  zoneName: string | null;
  locationText: string | null;
  status: 'OPEN' | 'CANCELLED';
  createdAt: string;
};

export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

async function request<T>(path: string, signal: AbortSignal, session?: Session, pin?: string): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (session) headers.Authorization = `Bearer ${session.token}`;
  if (pin !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(`/api/v1/${path}`, {
    method: pin === undefined ? 'GET' : 'POST',
    headers,
    signal,
    cache: 'no-store',
    redirect: 'error',
    body: pin === undefined ? undefined : JSON.stringify({ account: 'organizador', pin }),
  });
  if (!response.ok) {
    const message = response.status === 401
      ? pin === undefined ? 'La sesión venció. Vuelve a iniciar sesión.' : 'El servidor rechazó el PIN de organizador.'
      : response.status === 403 ? 'La cuenta no está autorizada para este evento.'
        : 'El servidor no pudo completar la consulta.';
    throw new ApiError(response.status, message);
  }
  if (!response.headers.get('content-type')?.startsWith('application/json')) {
    throw new ApiError(502, 'La respuesta no corresponde a la API de Discoprotect.');
  }
  return response.json() as Promise<T>;
}

export async function login(pin: string, signal: AbortSignal): Promise<Session> {
  const result = await request<Session & { role: string }>('demo/sessions', signal, undefined, pin);
  if (result.role !== 'ORGANIZER' || !/^[\w-]{43}$/.test(result.token)
      || !Number.isFinite(Date.parse(result.expiresAt)) || Date.parse(result.expiresAt) <= Date.now()) {
    throw new ApiError(403, 'El servidor no confirmó una sesión de organizador vigente.');
  }
  return { token: result.token, expiresAt: result.expiresAt };
}

export async function events(session: Session, signal: AbortSignal) {
  const result = await request<{ events: Event[] }>('events', signal, session);
  return result.events.filter(event => event.role === 'ORGANIZER');
}

export async function incidents(session: Session, eventId: string, signal: AbortSignal) {
  if (!eventId || eventId === '.' || eventId === '..') throw new ApiError(400, 'El evento no es válido.');
  const result = await request<{ incidents: Incident[] }>(
    `events/${encodeURIComponent(eventId)}/incidents`, signal, session,
  );
  if (result.incidents.some(incident => incident.eventId !== eventId || !Number.isFinite(Date.parse(incident.createdAt))
      || !['OPEN', 'CANCELLED'].includes(incident.status))) {
    throw new ApiError(502, 'La respuesta contiene solicitudes inválidas para el evento.');
  }
  return result.incidents;
}

export function messageFor(error: unknown) {
  return error instanceof ApiError ? error.message : 'No se pudo consultar el servidor. Comprueba Fastify y la conexión.';
}

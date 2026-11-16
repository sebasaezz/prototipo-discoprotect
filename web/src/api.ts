import { alertLabels, reasonLabels, type Review, type Triage, validReview, validTriage } from './triage.js';

export type Session = { token: string; expiresAt: string };
export type Event = { id: string; name: string; venue: string; role: string };
export const incidentLabels = {
  OPEN: 'Abierta', ASSIGNED: 'Asignada', IN_PROGRESS: 'En atención', RESOLVED: 'Resuelta', CANCELLED: 'Cancelada',
} as const;
export type Incident = {
  id: string;
  eventId: string;
  requesterName: string;
  zoneName: string | null;
  locationText: string | null;
  status: keyof typeof incidentLabels;
  createdAt: string;
  updatedAt: string;
  version: number;
  assignedStaff: { id: string; name: string } | null;
  assignedAt: string | null;
  startedAt: string | null;
  resolvedAt: string | null;
  resolutionNote: string | null;
  reasonCode?: keyof typeof reasonLabels | null;
  dangerAlert?: keyof typeof alertLabels | null;
  triage?: Triage;
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
  if (!Array.isArray(result.incidents) || result.incidents.some(incident => !validIncident(incident, eventId))
      || new Set(result.incidents.map(item => item.id)).size !== result.incidents.length) {
    throw new ApiError(502, 'La respuesta contiene solicitudes inválidas para el evento.');
  }
  return result.incidents;
}

export async function incidentTriage(session: Session, eventId: string, id: string, signal: AbortSignal) {
  if (!eventId || eventId === '.' || eventId === '..' || !/^[a-f0-9-]{36}$/.test(id)) {
    throw new ApiError(400, 'La solicitud no es válida.');
  }
  const result = await request<{ triage: Triage; reviews: Review[] }>(
    `events/${encodeURIComponent(eventId)}/incidents/${encodeURIComponent(id)}/triage`, signal, session,
  );
  if (!validTriage(result.triage) || !Array.isArray(result.reviews) || !result.reviews.every(validReview) ||
      result.reviews.some((review, index) => review.reviewVersion <= (result.reviews[index - 1]?.reviewVersion ?? 0)) ||
      (result.reviews.at(-1)?.reviewVersion ?? null) !== (result.triage.latestReview?.reviewVersion ?? null) ||
      Object.hasOwn(result, 'description')) {
    throw new ApiError(502, 'La respuesta de triaje no es válida.');
  }
  return result;
}

function validIncident(item: Incident, eventId: string) {
  if (!item || item.eventId !== eventId || typeof item.id !== 'string' ||
      typeof item.requesterName !== 'string' || !Object.hasOwn(incidentLabels, item.status) ||
      typeof item.createdAt !== 'string' || typeof item.updatedAt !== 'string' ||
      !Number.isSafeInteger(item.version) || item.version < 1) return false;
  if (Object.hasOwn(item, 'description') || item.reasonCode != null && !Object.hasOwn(reasonLabels, item.reasonCode) ||
      (item.dangerAlert ?? null) !== (item.reasonCode === 'UNRESPONSIVE' || item.reasonCode === 'IMMEDIATE_DANGER'
        ? item.reasonCode : null) || item.triage !== undefined && !validTriage(item.triage)) return false;
  const assigned = ['ASSIGNED', 'IN_PROGRESS', 'RESOLVED'].includes(item.status);
  if (assigned ? !item.assignedStaff?.id || !item.assignedStaff.name : item.assignedStaff !== null) return false;
  if ((typeof item.assignedAt === 'string') !== assigned ||
      (typeof item.startedAt === 'string') !== ['IN_PROGRESS', 'RESOLVED'].includes(item.status) ||
      (typeof item.resolvedAt === 'string') !== (item.status === 'RESOLVED')) return false;
  if (item.resolutionNote !== null && (item.status !== 'RESOLVED' || typeof item.resolutionNote !== 'string' ||
      !item.resolutionNote.trim() || item.resolutionNote.length > 240)) return false;
  const dates = [item.createdAt, item.assignedAt, item.startedAt, item.resolvedAt, item.updatedAt]
    .filter(value => value !== null).map(value => Date.parse(value));
  return dates.every((time, index) => Number.isFinite(time) && (index === 0 || dates[index - 1] <= time));
}

export function currentIncidentSnapshot(previous: Incident[] | null, incoming: Incident[]) {
  const known = new Map(previous?.map(item => [item.id, item]));
  return incoming.map(item => {
    const current = known.get(item.id);
    if (!current) return item;
    const operational = current.version > item.version ? current : item;
    const triage = (current.triage?.triageVersion ?? 0) > (item.triage?.triageVersion ?? 0) ? current.triage : item.triage;
    return operational.triage === triage ? operational : { ...operational, triage };
  });
}

export function messageFor(error: unknown) {
  return error instanceof ApiError ? error.message : 'No se pudo consultar el servidor. Comprueba Fastify y la conexión.';
}

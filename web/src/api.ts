import { alertLabels, reasonLabels, type Review, type Triage, validReview, validTriage } from './triage.js';

export type Session = { token: string; expiresAt: string };
export const eventStatusLabels = { PLANNED: 'Planificado', ACTIVE: 'Activo', ENDED: 'Finalizado' } as const;
export type Event = { id: string; name: string; venue: string; role: string; status: keyof typeof eventStatusLabels };
export const incidentLabels = {
  OPEN: 'Abierta', ASSIGNED: 'Asignada', IN_PROGRESS: 'En atención', RESOLVED: 'Resuelta', CANCELLED: 'Cancelada',
  CLOSED: 'Cerrada', WITHDRAWN: 'Retirada',
} as const;
type ActiveStatus = 'OPEN' | 'ASSIGNED' | 'IN_PROGRESS';
export const activeIncident = (item: Incident): item is Incident & { status: ActiveStatus } =>
  item.status === 'OPEN' || item.status === 'ASSIGNED' || item.status === 'IN_PROGRESS';
export const priorityLabels = {
  P0: 'P0 · Crítica', P1: 'P1 · Alta', P2: 'P2 · Media', P3: 'P3 · Baja', PENDING: 'Pendiente de evaluación',
} as const;
type Priority = keyof typeof priorityLabels;
type Location = { zoneId: string | null; zoneName: string | null; text: string | null };
export type Dispatch = {
  effectivePriority: Priority; prioritySource: string; priorityReasons: string[];
  elapsedSeconds: number; evaluatedAt: string; revision: number; humanPriority: Priority | null;
  confirmedLocation: Location | null; declaredLocation: Location | null; inferredLocation: Location | null;
  locationEvidence?: { messageSeq: number; quote: string; feature: string | null } | null;
  attendeeLocation?: { zoneId: string; zoneName: string; source: 'CONFIRMED_AI' | 'INDICATED'; messageSeq: number;
    evidence: { messageSeq: number; quote: string; feature: string | null } } | null;
  operational?: { situation: string; pending: string[]; guidance: string | null;
    risks: { kind: string; messageSeq: number; quote: string; uncertain: boolean }[] } | null;
  analysis: { summary: string; uncertainty: string[]; observedAt: string;
    locationEvidence?: { messageSeq: number; quote: string; feature: string | null } } | null;
};
type DispatchAudit = {
  dispatch: Dispatch;
  reviews: { revision: number; actor: { name: string }; reviewedAt: string; humanPriority: Priority | null; reason: string }[];
  transitions: { version: number; fromStatus: Incident['status']; toStatus: Incident['status']; actorName: string; appliedAt: string }[];
};
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
  hasConversation?: boolean;
  dispatch?: Dispatch;
};

export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

export type PurgePreview = { enabled: boolean; count: number; selectionHash: string | null };
export type PurgeCommand = { incidentId?: string; selectionHash: string; operationId: string; confirmation: 'ELIMINAR' };
export type Withdrawal = { version: number; operationId: string; resolutionNote: string };
const eventIncidentsPath = (eventId: string) => `events/${encodeURIComponent(eventId)}/incidents`;

export function previewDemoPurge(session: Session, event: string, signal: AbortSignal, id?: string) {
  return request<PurgePreview>(eventIncidentsPath(event) + '/demo-purge' +
    (id ? '?incidentId=' + encodeURIComponent(id) : ''), signal, session);
}

async function incidentCommand<T>(session: Session, path: string, command: PurgeCommand | Withdrawal): Promise<T> {
  const response = await fetch('/api/v1/' + path, {
    method: 'POST', headers: { Authorization: 'Bearer ' + session.token, 'Content-Type': 'application/json' },
    body: JSON.stringify(command), cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(30_000),
  });
  const result = await response.json();
  if (!response.ok) throw new ApiError(response.status, result.error?.message ?? 'El servidor rechazó la operación.');
  return result as T;
}

export const purgeDemo = (session: Session, event: string, command: PurgeCommand) =>
  incidentCommand<{ count: number; backupName: string }>(session, eventIncidentsPath(event) + '/demo-purge', command);

export const withdrawIncident = (session: Session, incident: Incident, command: Withdrawal) =>
  incidentCommand(session, eventIncidentsPath(incident.eventId) + '/' + encodeURIComponent(incident.id) + '/withdraw', command);

export async function request<T>(path: string, signal: AbortSignal, session?: Session,
  command?: { method: 'POST' | 'PATCH'; body: unknown }): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (session) headers.Authorization = `Bearer ${session.token}`;
  if (command) headers['Content-Type'] = 'application/json';
  const response = await fetch(`/api/v1/${path}`, {
    method: command?.method ?? 'GET',
    headers,
    signal,
    cache: 'no-store',
    redirect: 'error',
    body: command ? JSON.stringify(command.body) : undefined,
  });
  if (!response.ok) {
    const message = response.status === 401
      ? session ? 'La sesión venció. Vuelve a iniciar sesión.' : 'El servidor rechazó el PIN de organizador.'
      : response.status === 403 ? 'La cuenta no está autorizada para este evento.'
        : response.status === 409 ? 'La configuración cambió. Revisa la versión vigente.'
          : response.status === 400 ? 'Revisa los campos enviados.'
            : response.status === 404 ? 'El elemento solicitado ya no está disponible.'
              : 'El servidor no pudo completar la consulta.';
    throw new ApiError(response.status, message);
  }
  if (!response.headers.get('content-type')?.startsWith('application/json')) {
    throw new ApiError(502, 'La respuesta no corresponde a la API de Discoprotect.');
  }
  return response.json() as Promise<T>;
}

export async function login(pin: string, signal: AbortSignal): Promise<Session> {
  const result = await request<Session & { role: string }>('demo/sessions', signal, undefined,
    { method: 'POST', body: { account: 'organizador', pin } });
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
    `events/${encodeURIComponent(eventId)}/incidents?order=dispatch`, signal, session,
  );
  if (!Array.isArray(result.incidents) || result.incidents.some(incident => !validIncident(incident, eventId))
      || new Set(result.incidents.map(item => item.id)).size !== result.incidents.length) {
    throw new ApiError(502, 'La respuesta contiene solicitudes inválidas para el evento.');
  }
  return result.incidents;
}

export async function incidentDispatch(session: Session, eventId: string, id: string, signal: AbortSignal) {
  if (!eventId || eventId === '.' || eventId === '..' || !/^[a-f0-9-]{36}$/.test(id)) {
    throw new ApiError(400, 'La solicitud no es válida.');
  }
  const result = await request<DispatchAudit>(
    `events/${encodeURIComponent(eventId)}/incidents/${encodeURIComponent(id)}/dispatch`, signal, session,
  );
  if (!validDispatch(result.dispatch) || !Array.isArray(result.reviews) || !Array.isArray(result.transitions) ||
      Object.hasOwn(result, 'messages')) throw new ApiError(502, 'La respuesta de despacho no es válida.');
  return result;
}

function validDispatch(value: Dispatch) {
  return value != null && Object.hasOwn(priorityLabels, value.effectivePriority) &&
    Number.isSafeInteger(value.revision) && value.revision >= 0 &&
    Number.isSafeInteger(value.elapsedSeconds) && value.elapsedSeconds >= 0 &&
    Array.isArray(value.priorityReasons) && value.priorityReasons.every(item => typeof item === 'string');
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
  if (item.dispatch !== undefined && !validDispatch(item.dispatch)) return false;
  if (Object.hasOwn(item, 'description') || item.reasonCode != null && !Object.hasOwn(reasonLabels, item.reasonCode) ||
      (item.dangerAlert ?? null) !== (item.reasonCode === 'UNRESPONSIVE' || item.reasonCode === 'IMMEDIATE_DANGER'
        ? item.reasonCode : null) || item.triage !== undefined && !validTriage(item.triage)) return false;
  const assigned = typeof item.assignedAt === 'string';
  if (['ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'].includes(item.status) && !assigned ||
      item.status === 'OPEN' && assigned) return false;
  if (assigned ? !item.assignedStaff?.id || !item.assignedStaff.name : item.assignedStaff !== null) return false;
  if (['IN_PROGRESS', 'RESOLVED'].includes(item.status) && typeof item.startedAt !== 'string' ||
      typeof item.startedAt === 'string' && (!assigned || item.status === 'ASSIGNED') ||
      (typeof item.resolvedAt === 'string') !== (item.status === 'RESOLVED')) return false;
  if (item.resolutionNote !== null && (!['RESOLVED', 'CLOSED', 'WITHDRAWN'].includes(item.status) || typeof item.resolutionNote !== 'string' ||
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

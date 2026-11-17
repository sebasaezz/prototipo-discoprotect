import { ApiError, request, type Session } from './api.js';

export const zoneKinds = {
  STAGE: 'Escenario', FLOOR: 'Pista', ACCESS: 'Acceso', STANDS: 'Gradería', SERVICES: 'Servicios', OTHER: 'Otra función',
} as const;
export const pointKinds = {
  MEDICAL: 'Puesto médico', SECURITY: 'Puesto de seguridad', ENTRANCE: 'Entrada', EXIT: 'Salida', MEETING: 'Punto de encuentro',
} as const;

type VenueFields = {
  name: string; venue: string; description: string | null; accessNotes: string | null; responseNotes: string | null;
};
type ZoneFields = {
  name: string; description: string | null; kind: keyof typeof zoneKinds; reference: string | null; active: boolean;
};
type PointFields = { name: string; kind: keyof typeof pointKinds; zoneId: string; reference: string; active: boolean };
export type VenueZone = ZoneFields & { id: string };
export type OperationalPoint = PointFields & { id: string; zoneName: string };
export type VenueConfiguration = { eventId: string; version: number; venue: VenueFields; zones: VenueZone[]; points: OperationalPoint[] };
export type VenueChange = { entity: 'venue'; fields: VenueFields }
  | { entity: 'zone'; id?: string; fields: ZoneFields }
  | { entity: 'point'; id?: string; fields: PointFields };

function eventPath(eventId: string) {
  if (!eventId || eventId === '.' || eventId === '..') throw new ApiError(400, 'El evento no es válido.');
  return 'events/' + encodeURIComponent(eventId);
}

export async function readVenue(session: Session, eventId: string, signal: AbortSignal) {
  const config = await request<VenueConfiguration>(eventPath(eventId) + '/venue', signal, session);
  if (!config || config.eventId !== eventId || !Number.isSafeInteger(config.version) || config.version < 1 ||
      !config.venue || !Array.isArray(config.zones) || !Array.isArray(config.points)) {
    throw new ApiError(502, 'La configuración recibida no corresponde al evento seleccionado.');
  }
  return config;
}

export function writeVenue(session: Session, eventId: string, version: number, change: VenueChange, signal: AbortSignal) {
  const base = eventPath(eventId);
  const body = { ...change.fields, version };
  if (change.entity === 'venue') return request(base, signal, session, { method: 'PATCH', body });
  const collection = base + (change.entity === 'zone' ? '/zones' : '/operational-points');
  if (change.id === '.' || change.id === '..') throw new ApiError(400, 'El elemento no es válido.');
  return change.id ? request(collection + '/' + encodeURIComponent(change.id), signal, session, { method: 'PATCH', body })
    : request(collection, signal, session, { method: 'POST', body });
}

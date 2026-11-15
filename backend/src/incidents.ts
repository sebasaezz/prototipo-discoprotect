import { randomUUID } from 'node:crypto';
import { z } from 'zod';
import { requireMembership, type Session } from './auth.js';
import { atomic, type Db } from './db.js';
import { ApiError } from './errors.js';

export const createInput = z.strictObject({
  clientRequestId: z.uuid().transform(value => value.toLowerCase()),
  zoneId: z.string().min(1).max(128).nullish().transform(value => value ?? null),
  locationText: z.string().trim().max(160).nullish().transform(value => value || null),
});
export const incidentId = z.strictObject({ id: z.uuid() });

type Incident = {
  id: string;
  eventId: string;
  requesterPersonId: string;
  requesterName: string;
  zoneId: string | null;
  zoneName: string | null;
  locationText: string | null;
  status: 'OPEN' | 'CANCELLED';
  clientRequestId: string;
  createdAt: string;
  updatedAt: string;
};

const selection = `SELECT i.id, i.event_id AS eventId, i.requester_person_id AS requesterPersonId,
  p.name AS requesterName, i.zone_id AS zoneId, z.name AS zoneName, i.location_text AS locationText,
  i.status, i.client_request_id AS clientRequestId, i.created_at AS createdAt, i.updated_at AS updatedAt
  FROM incidents i JOIN people p ON p.id = i.requester_person_id
  LEFT JOIN zones z ON z.event_id = i.event_id AND z.id = i.zone_id`;

export function zones(db: Db, eventId: string) {
  return db.prepare('SELECT id, name FROM zones WHERE event_id = ? ORDER BY name, id').all(eventId);
}

export function ownIncidents(db: Db, eventId: string, personId: string) {
  return db.prepare(`${selection} WHERE i.event_id = ? AND i.requester_person_id = ?
    ORDER BY i.created_at DESC, i.id DESC`).all(eventId, personId) as Incident[];
}

export function eventIncidents(db: Db, eventId: string) {
  const rows = db.prepare(`${selection} WHERE i.event_id = ? ORDER BY i.created_at DESC, i.id DESC`)
    .all(eventId) as Incident[];
  return rows.map(({ clientRequestId, requesterPersonId, ...incident }) => incident);
}

export function createIncident(db: Db, session: Session, eventId: string, input: z.output<typeof createInput>) {
  return atomic(db, () => {
    requireMembership(db, session, eventId);
    const previous = db.prepare(`${selection} WHERE i.requester_person_id = ? AND i.client_request_id = ?`)
      .get(session.personId, input.clientRequestId) as Incident | undefined;
    if (previous) {
      if (previous.eventId !== eventId || previous.zoneId !== input.zoneId || previous.locationText !== input.locationText) {
        throw new ApiError(409, 'REQUEST_CONFLICT', 'La clave de solicitud ya se usó con otros datos');
      }
      return { created: false, incident: previous };
    }
    if (input.zoneId !== null && !db.prepare('SELECT 1 FROM zones WHERE event_id = ? AND id = ?').get(eventId, input.zoneId)) {
      throw new ApiError(400, 'INVALID_INPUT', 'La zona no pertenece al evento');
    }
    const id = randomUUID();
    const now = new Date().toISOString();
    db.prepare(`INSERT INTO incidents (id, event_id, requester_person_id, zone_id, location_text,
      status, client_request_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?, ?)`)
      .run(id, eventId, session.personId, input.zoneId, input.locationText, input.clientRequestId, now, now);
    return { created: true, incident: db.prepare(`${selection} WHERE i.id = ?`).get(id) as Incident };
  });
}

export function cancelIncident(db: Db, session: Session, id: string) {
  return atomic(db, () => {
    const previous = db.prepare(`${selection} WHERE i.id = ? AND i.requester_person_id = ?`)
      .get(id, session.personId) as Incident | undefined;
    if (!previous) throw new ApiError(404, 'INCIDENT_NOT_FOUND', 'No se encontró la solicitud');
    requireMembership(db, session, previous.eventId);
    if (previous.status !== 'OPEN') {
      throw new ApiError(409, 'INCIDENT_NOT_OPEN', 'La solicitud ya no está abierta');
    }
    const updatedAt = new Date(Math.max(Date.now(), Date.parse(previous.updatedAt))).toISOString();
    db.prepare("UPDATE incidents SET status = 'CANCELLED', updated_at = ? WHERE id = ?").run(updatedAt, id);
    return { ...previous, status: 'CANCELLED' as const, updatedAt };
  });
}

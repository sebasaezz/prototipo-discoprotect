import { randomUUID } from 'node:crypto';
import { z } from 'zod';
import { requireMembership, requireRole, type Session } from './auth.js';
import { atomic, type Db } from './db.js';
import { ApiError } from './errors.js';
import { eventForMember, eventEdit, version } from './records.js';

const name = z.string().trim().min(1).max(160).regex(/^[^\p{Cc}\p{Cf}]+$/u);
const notes = (max: number) => z.string().trim().min(1).max(max)
  .refine(value => !/[\p{Cc}\p{Cf}]/u.test(value.replace(/[\r\n\t]/gu, ''))).nullable();
const zoneKind = z.enum(['STAGE', 'FLOOR', 'ACCESS', 'STANDS', 'SERVICES', 'OTHER']);
const pointKind = z.enum(['MEDICAL', 'SECURITY', 'ENTRANCE', 'EXIT', 'MEETING']);
export const venueEdit = z.union([
  version.extend({ name, venue: name, description: notes(600), accessNotes: notes(300), responseNotes: notes(300) }),
  eventEdit,
]);
export const zoneEdit = version.extend({
  name, description: notes(240), active: z.boolean(),
  kind: zoneKind.optional(), reference: notes(160).optional(),
});
export const pointEdit = version.extend({ name, kind: pointKind, zoneId: name.max(128), reference: name, active: z.boolean() });
const timestamp = z.iso.datetime({ offset: true }).transform(value => new Date(value).toISOString());
const scheduleFields = z.strictObject({
  startsAt: timestamp, endsAt: timestamp, status: z.enum(['PLANNED', 'ACTIVE', 'ENDED']),
});
const validSchedule = (value: { startsAt: string; endsAt: string }) => value.startsAt < value.endsAt;
export const eventCreation = scheduleFields.extend({ id: z.uuid(), name, venue: name, demoAttendees: z.boolean() }).refine(validSchedule);
export const eventSchedule = scheduleFields.extend(version.shape).refine(validSchedule);

type Venue = { name: string; venue: string; description: string | null; accessNotes: string | null; responseNotes: string | null };
export type Zone = Omit<z.infer<typeof zoneEdit>, 'version' | 'kind' | 'reference'> & {
  id: string; kind: z.infer<typeof zoneKind>; reference: string | null;
};
export type Point = Omit<z.infer<typeof pointEdit>, 'version'> & { id: string; zoneName: string };

export function venueInfo(db: Db, eventId: string) {
  return db.prepare(`SELECT name, venue, venue_description AS description, access_notes AS accessNotes,
    response_notes AS responseNotes, config_version AS version FROM events WHERE id = ?`).get(eventId) as
    (Venue & { version: number }) | undefined;
}

export function venueZones(db: Db, eventId: string, includeInactive = false) {
  const rows = db.prepare(`SELECT id, name, description, kind, reference, active FROM zones
    WHERE event_id = ? AND (? OR active = 1) ORDER BY name, id`).all(eventId, Number(includeInactive)) as
    (Omit<Zone, 'active'> & { active: number })[];
  return rows.map(row => ({ ...row, active: row.active === 1 }));
}

export function venuePoints(db: Db, eventId: string, includeInactive = false) {
  const rows = db.prepare(`SELECT p.id, p.name, p.kind, p.zone_id AS zoneId, z.name AS zoneName, p.reference, p.active
    FROM operational_points p JOIN zones z ON z.event_id = p.event_id AND z.id = p.zone_id
    WHERE p.event_id = ? AND (? OR (p.active = 1 AND z.active = 1)) ORDER BY p.name, p.id`)
    .all(eventId, Number(includeInactive)) as (Omit<Point, 'active'> & { active: number })[];
  return rows.map(row => ({ ...row, active: row.active === 1 }));
}

export function configuration(db: Db, eventId: string, organizer: boolean) {
  const current = venueInfo(db, eventId);
  if (!current) throw new ApiError(404, 'EVENT_NOT_FOUND', 'No se encontró el evento');
  const { version, ...venue } = current;
  return { eventId, venue, zones: venueZones(db, eventId, organizer), points: venuePoints(db, eventId, organizer),
    ...(organizer ? { version } : {}) };
}

type Entity = 'VENUE' | 'ZONE' | 'POINT';
type Action = 'CREATE' | 'UPDATE' | 'ACTIVATE' | 'DEACTIVATE';
function change<T>(db: Db, session: Session, eventId: string, expected: number | undefined,
  entity: Entity, id: string, write: () => { action: Action; result: T }) {
  return atomic(db, () => {
    requireRole(session, 'ORGANIZER');
    requireMembership(db, session, eventId);
    const current = venueInfo(db, eventId)!.version;
    if (expected !== undefined && expected !== current) {
      throw new ApiError(409, 'VENUE_CONFLICT', 'La configuración cambió. Revisa la versión vigente.', { currentVersion: current });
    }
    const { action, result } = write();
    db.prepare('UPDATE events SET config_version = ? WHERE id = ?').run(current + 1, eventId);
    db.prepare('INSERT INTO venue_configuration_changes VALUES (?, ?, ?, ?, ?, ?, ?)')
      .run(eventId, current + 1, session.personId, new Date().toISOString(), entity, id, action);
    return { result, version: current + 1 };
  });
}

export function saveVenue(db: Db, session: Session, eventId: string, value: z.infer<typeof venueEdit>) {
  const operational = 'version' in value ? value : undefined;
  const saved = change(db, session, eventId, operational?.version, 'VENUE', eventId, () => {
    if (operational) {
      db.prepare('UPDATE events SET name = ?, venue = ?, venue_description = ?, access_notes = ?, response_notes = ? WHERE id = ?')
        .run(value.name, value.venue, operational.description, operational.accessNotes, operational.responseNotes, eventId);
    } else {
      db.prepare('UPDATE events SET name = ?, venue = ? WHERE id = ?').run(value.name, value.venue, eventId);
    }
    return { action: 'UPDATE', result: eventForMember(db, eventId, session.personId) };
  });
  return operational ? { ...saved.result, version: saved.version } : saved.result;
}

export function readSchedule(db: Db, eventId: string) {
  return db.prepare(`SELECT starts_at AS startsAt, ends_at AS endsAt, status, config_version AS version
    FROM events WHERE id = ?`).get(eventId);
}

export function saveSchedule(db: Db, session: Session, eventId: string, value: z.infer<typeof eventSchedule>) {
  const saved = change(db, session, eventId, value.version, 'VENUE', eventId, () => {
    db.prepare('UPDATE events SET starts_at = ?, ends_at = ?, status = ? WHERE id = ?')
      .run(value.startsAt, value.endsAt, value.status, eventId);
    return { action: 'UPDATE', result: eventForMember(db, eventId, session.personId) };
  });
  return { ...saved.result, version: saved.version };
}

export function createEvent(db: Db, session: Session, value: z.infer<typeof eventCreation>) {
  requireRole(session, 'ORGANIZER');
  return atomic(db, () => {
    const present = db.prepare('SELECT 1 FROM events WHERE id = ?').get(value.id);
    if (present) {
      requireMembership(db, session, value.id);
      const current = eventForMember(db, value.id, session.personId);
      if (current?.name === value.name && current.venue === value.venue && current.startsAt === value.startsAt &&
        current.endsAt === value.endsAt && current.status === value.status) return current;
      throw new ApiError(409, 'EVENT_EXISTS', 'El identificador corresponde a una configuración existente');
    }
    db.prepare('INSERT INTO events (id, name, venue, starts_at, ends_at, status) VALUES (?, ?, ?, ?, ?, ?)')
      .run(value.id, value.name, value.venue, value.startsAt, value.endsAt, value.status);
    const member = db.prepare('INSERT INTO event_memberships (id, event_id, person_id, role) VALUES (?, ?, ?, ?)');
    member.run(randomUUID(), value.id, session.personId, 'ORGANIZER');
    if (value.demoAttendees) {
      for (const person of db.prepare("SELECT id FROM people WHERE demo_key IN ('antonia', 'martin', 'sofia')").all()) {
        member.run(randomUUID(), value.id, person.id, 'ATTENDEE');
      }
    }
    return eventForMember(db, value.id, session.personId);
  });
}

function updateAction(wasActive: unknown, active: boolean): Action {
  return Boolean(wasActive) === active ? 'UPDATE' : active ? 'ACTIVATE' : 'DEACTIVATE';
}

export function saveZone(db: Db, session: Session, eventId: string, value: z.infer<typeof zoneEdit>, zoneId?: string) {
  const id = zoneId ?? randomUUID();
  const { version, ...fields } = value;
  const saved = change(db, session, eventId, version, 'ZONE', id, () => {
    const previous = zoneId === undefined ? undefined : db.prepare(
      'SELECT kind, reference, active FROM zones WHERE event_id = ? AND id = ?').get(eventId, id) as
      Pick<Zone, 'kind' | 'reference'> & { active: number } | undefined;
    if (zoneId !== undefined && !previous) throw new ApiError(404, 'ZONE_NOT_FOUND', 'No se encontró la zona');
    const zone: Zone = { id, ...fields, kind: fields.kind ?? previous?.kind ?? 'OTHER',
      reference: fields.reference === undefined ? previous?.reference ?? null : fields.reference };
    const params = [zone.name, zone.description, zone.kind, zone.reference, Number(zone.active)] as const;
    if (zoneId === undefined) {
      db.prepare('INSERT INTO zones (event_id, id, name, description, kind, reference, active) VALUES (?, ?, ?, ?, ?, ?, ?)')
        .run(eventId, id, ...params);
      return { action: 'CREATE', result: zone };
    }
    db.prepare('UPDATE zones SET name = ?, description = ?, kind = ?, reference = ?, active = ? WHERE event_id = ? AND id = ?')
      .run(...params, eventId, id);
    return { action: updateAction(previous?.active, zone.active), result: zone };
  });
  return { zone: saved.result, version: saved.version };
}

export function savePoint(db: Db, session: Session, eventId: string, value: z.infer<typeof pointEdit>, pointId?: string) {
  const id = pointId ?? randomUUID();
  const { version, ...fields } = value;
  const saved = change(db, session, eventId, version, 'POINT', id, () => {
    const previous = pointId === undefined ? null :
      db.prepare('SELECT active FROM operational_points WHERE event_id = ? AND id = ?').get(eventId, id);
    if (previous === undefined) throw new ApiError(404, 'POINT_NOT_FOUND', 'No se encontró el punto operativo');
    const zone = db.prepare('SELECT name FROM zones WHERE event_id = ? AND id = ?').get(eventId, fields.zoneId);
    if (!zone) throw new ApiError(400, 'INVALID_ZONE', 'Selecciona una zona de este evento');
    const params = [fields.zoneId, fields.name, fields.kind, fields.reference, Number(fields.active)] as const;
    if (previous === null) {
      db.prepare('INSERT INTO operational_points (event_id, id, zone_id, name, kind, reference, active) VALUES (?, ?, ?, ?, ?, ?, ?)')
        .run(eventId, id, ...params);
    } else {
      db.prepare('UPDATE operational_points SET zone_id = ?, name = ?, kind = ?, reference = ?, active = ? WHERE event_id = ? AND id = ?')
        .run(...params, eventId, id);
    }
    return { action: previous === null ? 'CREATE' : updateAction(previous.active, fields.active),
      result: { id, ...fields, zoneName: String(zone.name) } };
  });
  return { point: saved.result, version: saved.version };
}

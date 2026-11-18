import { randomUUID } from 'node:crypto';
import { z } from 'zod';
import { requireMembership, type Session } from './auth.js';
import { atomic, type Db } from './db.js';
import { ApiError } from './errors.js';

export const staffChange = z.strictObject({
  personId: z.string().trim().min(1).max(160),
  active: z.boolean(),
  replacementId: z.string().trim().min(1).max(160).optional(),
  operationId: z.uuid().transform(value => value.toLowerCase()),
}).refine(input => !input.replacementId || !input.active && input.replacementId !== input.personId);

export function eventStaff(db: Db, eventId: string) {
  const members = db.prepare(`SELECT p.id, p.name, 'STAFF' AS role, m.id IS NOT NULL AS active,
    i.id AS incidentId, i.status AS incidentStatus
    FROM people p
    LEFT JOIN event_memberships m ON m.event_id = ? AND m.person_id = p.id AND m.role = 'STAFF'
    LEFT JOIN incidents i ON i.event_id = m.event_id AND i.assigned_staff_id = p.id
      AND i.status IN ('ASSIGNED', 'IN_PROGRESS')
    WHERE p.demo_key IN ('guardia', 'guardia2') ORDER BY p.name, p.id`).all(eventId);
  return { members: members.map(member => ({ ...member, active: Boolean(member.active) })) };
}

export function changeEventStaff(db: Db, actor: Session, eventId: string, input: z.infer<typeof staffChange>) {
  return atomic(db, () => {
    requireMembership(db, actor, eventId);
    const { personId, active, replacementId, operationId } = input;
    const previous = db.prepare(`SELECT event_id, staff_person_id, active, replacement_person_id, changed
      FROM event_staff_audit WHERE actor_person_id = ? AND operation_id = ?`).get(actor.personId, operationId);
    if (previous) {
      if (previous.event_id !== eventId || previous.staff_person_id !== personId ||
        previous.active !== Number(active) || previous.replacement_person_id !== (replacementId ?? null)) {
        throw new ApiError(409, 'OPERATION_CONFLICT', 'La operación se utilizó con otros parámetros');
      }
      return { changed: Boolean(previous.changed), replayed: true };
    }
    if (!db.prepare("SELECT 1 FROM people WHERE id = ? AND demo_key IN ('guardia', 'guardia2')").get(personId)) {
      throw new ApiError(404, 'STAFF_NOT_FOUND', 'La cuenta no corresponde a un guardia de demostración');
    }
    const member = db.prepare('SELECT role FROM event_memberships WHERE event_id = ? AND person_id = ?').get(eventId, personId);
    if (member && member.role !== 'STAFF') {
      throw new ApiError(409, 'STAFF_ROLE_CONFLICT', 'La cuenta tiene un rol distinto en el evento');
    }
    const changed = Boolean(member) !== active;
    const now = new Date().toISOString();
    let reassignedId: string | null = null;
    if (changed && active) {
      db.prepare("INSERT INTO event_memberships (id, event_id, person_id, role) VALUES (?, ?, ?, 'STAFF')")
        .run(randomUUID(), eventId, personId);
    }
    if (changed && !active) {
      const assignment = db.prepare(`SELECT id FROM incidents WHERE event_id = ? AND assigned_staff_id = ?
        AND status IN ('ASSIGNED', 'IN_PROGRESS')`).get(eventId, personId);
      if (assignment) {
        if (!replacementId) throw new ApiError(409, 'REPLACEMENT_REQUIRED', 'Selecciona un reemplazo para la atención activa');
        requireMembership(db, { personId: replacementId, role: 'STAFF' }, eventId);
        if (db.prepare("SELECT 1 FROM incidents WHERE assigned_staff_id = ? AND status IN ('ASSIGNED', 'IN_PROGRESS')")
          .get(replacementId)) {
          throw new ApiError(409, 'REPLACEMENT_BUSY', 'El guardia de reemplazo tiene una atención activa');
        }
        reassignedId = String(assignment.id);
        db.prepare('UPDATE incidents SET assigned_staff_id = ?, updated_at = max(updated_at, ?), version = version + 1 WHERE id = ?')
          .run(replacementId, now, reassignedId);
      }
      db.prepare("DELETE FROM event_memberships WHERE event_id = ? AND person_id = ? AND role = 'STAFF'").run(eventId, personId);
      db.prepare("DELETE FROM demo_sessions WHERE person_id = ? AND role = 'STAFF'").run(personId);
    }
    db.prepare('INSERT INTO event_staff_audit VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)')
      .run(actor.personId, operationId, eventId, personId, Number(active), Number(changed), replacementId ?? null, reassignedId, now);
    return { changed, replayed: false };
  });
}

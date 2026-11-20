import { z } from 'zod';
import type { Db } from './db.js';
import { listPage } from './event-services.js';
import { statisticsQuery } from './statistics.js';

export const searchQuery = listPage.extend({
  type: z.enum(['incidents', 'zones', 'staff', 'tickets']),
  id: z.uuid().optional(),
  text: z.string().trim().min(1).max(80).optional(),
  status: statisticsQuery.shape.status,
}).refine(query => {
  switch (query.type) {
    case 'tickets': return Boolean(query.id) && !query.text && !query.status;
    case 'incidents': return !query.text;
    case 'zones':
    case 'staff': return !query.id && !query.status;
  }
});

export function eventSearch(db: Db, eventId: string, query: z.infer<typeof searchQuery>, organizer: boolean) {
  switch (query.type) {
    case 'incidents':
      return db.prepare(`SELECT id, status, created_at AS createdAt FROM incidents
        WHERE event_id = ? AND (? IS NULL OR id = ?) AND (? IS NULL OR status = ?)
        ORDER BY created_at DESC, id DESC LIMIT 25 OFFSET ?`)
        .all(eventId, query.id ?? null, query.id ?? null, query.status ?? null, query.status ?? null, query.offset);
    case 'zones':
      return db.prepare(`SELECT id, name, active FROM zones
        WHERE event_id = ? AND (? OR active = 1) AND instr(lower(name), lower(?)) > 0
        ORDER BY name, id LIMIT 25 OFFSET ?`).all(eventId, Number(organizer), query.text ?? '', query.offset);
    case 'staff':
      return db.prepare(`SELECT p.id, p.name FROM event_memberships m JOIN people p ON p.id = m.person_id
        WHERE m.event_id = ? AND m.role = 'STAFF' AND instr(lower(p.name), lower(?)) > 0
        ORDER BY p.name, p.id LIMIT 25 OFFSET ?`).all(eventId, query.text ?? '', query.offset);
    case 'tickets':
      return db.prepare(`SELECT id, expires_at AS expiresAt, used_at AS usedAt, revoked_at AS revokedAt
        FROM event_tickets WHERE event_id = ? AND id = ? LIMIT 25 OFFSET ?`).all(eventId, query.id!, query.offset);
  }
}

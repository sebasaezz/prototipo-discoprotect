import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { z } from 'zod';
import { requireMembership, type Session } from './auth.js';
import { atomic, type Db } from './db.js';
import { ApiError } from './errors.js';

const uuid = z.uuid().transform(value => value.toLowerCase());
const utc = z.iso.datetime({ offset: true }).transform(value => new Date(value).toISOString());
const opaqueCode = (prefix: string) => prefix + randomBytes(24).toString('base64url');
const hash = (value: string) => createHash('sha256').update(value).digest('hex');

export const issueTicketInput = z.strictObject({ attendeeId: z.string().trim().min(1).max(160), expiresAt: utc });
export const ticketValidationInput = z.strictObject({ code: z.string().trim().min(1).max(120), operationId: uuid });
export const listPage = z.strictObject({ offset: z.coerce.number().int().min(0).max(10000).default(0) });
export function ticketCandidates(db: Db, eventId: string, offset: number) {
  return { attendees: db.prepare(`SELECT p.id, p.name, t.id IS NOT NULL AS issued, t.expires_at AS expiresAt,
    t.used_at AS usedAt, t.revoked_at AS revokedAt, t.id AS ticketId
    FROM event_memberships m JOIN people p ON p.id = m.person_id
    LEFT JOIN event_tickets t ON t.event_id = m.event_id AND t.attendee_id = m.person_id
    WHERE m.event_id = ? AND m.role = 'ATTENDEE' ORDER BY p.name, p.id LIMIT 25 OFFSET ?`).all(eventId, offset) };
}

export function issueTicket(db: Db, actor: Session, eventId: string, input: z.infer<typeof issueTicketInput>) {
  return atomic(db, () => {
    requireMembership(db, actor, eventId);
    const attendee = db.prepare("SELECT 1 FROM event_memberships WHERE event_id = ? AND person_id = ? AND role = 'ATTENDEE'")
      .get(eventId, input.attendeeId);
    if (!attendee) throw new ApiError(404, 'ATTENDEE_NOT_FOUND', 'La cuenta no participa en este evento');
    const previous = db.prepare('SELECT id, expires_at FROM event_tickets WHERE event_id = ? AND attendee_id = ?')
      .get(eventId, input.attendeeId);
    if (previous?.expires_at === input.expiresAt) return { id: previous.id };
    if (previous) throw new ApiError(409, 'TICKET_EXISTS', 'La cuenta ya tiene una entrada para el evento');
    if (Date.parse(input.expiresAt) <= Date.now()) throw new ApiError(400, 'EXPIRED_TICKET', 'La vigencia debe terminar en el futuro');
    const id = randomUUID();
    db.prepare('INSERT INTO event_tickets (id, event_id, attendee_id, code, expires_at) VALUES (?, ?, ?, ?, ?)')
      .run(id, eventId, input.attendeeId, opaqueCode('DP1_'), input.expiresAt);
    return { id };
  });
}

export function revokeTicket(db: Db, actor: Session, eventId: string, id: string) {
  return atomic(db, () => {
    requireMembership(db, actor, eventId);
    const result = db.prepare('UPDATE event_tickets SET revoked_at = coalesce(revoked_at, ?) WHERE id = ? AND event_id = ?')
      .run(new Date().toISOString(), id, eventId);
    if (!result.changes) throw new ApiError(404, 'TICKET_NOT_FOUND', 'No se encontró la entrada');
    return { revoked: true };
  });
}

export function ownTicket(db: Db, eventId: string, personId: string) {
  return { ticket: db.prepare(`SELECT code, expires_at AS expiresAt, used_at AS usedAt, revoked_at AS revokedAt
    FROM event_tickets WHERE event_id = ? AND attendee_id = ?`).get(eventId, personId) ?? null };
}

export function validateTicket(db: Db, actor: Session, eventId: string, input: z.infer<typeof ticketValidationInput>) {
  return atomic(db, () => {
    requireMembership(db, actor, eventId);
    const codeHash = hash(input.code);
    const previous = db.prepare('SELECT event_id, code_hash, result FROM ticket_validations WHERE actor_person_id = ? AND operation_id = ?')
      .get(actor.personId, input.operationId);
    if (previous) {
      if (previous.event_id !== eventId || previous.code_hash !== codeHash) {
        throw new ApiError(409, 'OPERATION_CONFLICT', 'La operación se utilizó con otros parámetros');
      }
      return { result: previous.result, replayed: true };
    }
    const ticket = /^DP1_[A-Za-z0-9_-]{32}$/u.test(input.code)
      ? db.prepare('SELECT id, event_id, expires_at, revoked_at, used_at FROM event_tickets WHERE code = ?').get(input.code)
      : undefined;
    const now = new Date().toISOString();
    const result = !ticket ? 'INVALID' : ticket.event_id !== eventId ? 'OTHER_EVENT' : ticket.revoked_at ? 'REVOKED'
      : String(ticket.expires_at) <= now ? 'EXPIRED' : ticket.used_at ? 'USED' : 'VALID';
    if (result === 'VALID') db.prepare('UPDATE event_tickets SET used_at = ? WHERE id = ?').run(now, ticket!.id);
    db.prepare('INSERT INTO ticket_validations VALUES (?, ?, ?, ?, ?, ?)')
      .run(actor.personId, input.operationId, eventId, codeHash, result, now);
    return { result, replayed: false };
  });
}

const https = z.string().trim().max(500).url().refine(value => {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && !url.username && !url.password;
  } catch { return false; }
}).nullable();
export const promotionInput = z.strictObject({
  title: z.string().trim().min(1).max(100), description: z.string().trim().min(1).max(1000),
  imageUrl: https, linkUrl: https, startsAt: utc, endsAt: utc, active: z.boolean(),
  version: z.number().int().positive().optional(),
}).refine(input => input.startsAt < input.endsAt);

const promotionFields = `id, title, description, image_url AS imageUrl, link_url AS linkUrl,
  starts_at AS startsAt, ends_at AS endsAt, active, version`;
export function promotions(db: Db, eventId: string, attendee: boolean, offset: number) {
  const cutoff = new Date().toISOString();
  const rows = attendee ? db.prepare(`SELECT ${promotionFields} FROM event_promotions
    WHERE event_id = ? AND active = 1 AND starts_at <= ? AND ends_at > ? ORDER BY starts_at DESC, id LIMIT 25 OFFSET ?`)
    .all(eventId, cutoff, cutoff, offset) : db.prepare(`SELECT ${promotionFields} FROM event_promotions
    WHERE event_id = ? ORDER BY starts_at DESC, id LIMIT 25 OFFSET ?`).all(eventId, offset);
  return { promotions: rows.map(row => ({ ...row, active: Boolean(row.active) })) };
}

export function savePromotion(db: Db, actor: Session, eventId: string, input: z.infer<typeof promotionInput>, id?: string) {
  return atomic(db, () => {
    requireMembership(db, actor, eventId);
    const titleKey = input.title.normalize('NFKC').toLocaleLowerCase('es').replace(/\s+/gu, ' ');
    if (db.prepare('SELECT 1 FROM event_promotions WHERE event_id = ? AND title_key = ? AND id <> ?')
      .get(eventId, titleKey, id ?? '')) throw new ApiError(409, 'PROMOTION_EXISTS', 'Ya existe una promoción con ese título');
    const fields = [input.title, titleKey, input.description, input.imageUrl, input.linkUrl,
      input.startsAt, input.endsAt, Number(input.active)];
    if (!id) {
      if (input.version !== undefined) throw new ApiError(400, 'UNEXPECTED_VERSION', 'La creación no admite versión');
      id = randomUUID();
      db.prepare(`INSERT INTO event_promotions (id, event_id, title, title_key, description, image_url,
        link_url, starts_at, ends_at, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`).run(id, eventId, ...fields);
    } else {
      const current = db.prepare('SELECT version FROM event_promotions WHERE id = ? AND event_id = ?').get(id, eventId);
      if (!current) throw new ApiError(404, 'PROMOTION_NOT_FOUND', 'No se encontró la promoción');
      if (current.version !== input.version) throw new ApiError(409, 'VERSION_CONFLICT', 'Consulta la promoción vigente antes de editar');
      db.prepare(`UPDATE event_promotions SET title = ?, title_key = ?, description = ?, image_url = ?, link_url = ?,
        starts_at = ?, ends_at = ?, active = ?, version = version + 1 WHERE id = ? AND event_id = ?`).run(...fields, id, eventId);
    }
    return db.prepare(`SELECT ${promotionFields} FROM event_promotions WHERE id = ?`).get(id);
  });
}

export const companionAcceptInput = z.strictObject({ code: z.string().regex(/^DPC_[A-Za-z0-9_-]{32}$/u) });
export function companionInvite(db: Db, actor: Session, eventId: string) {
  return atomic(db, () => {
    requireMembership(db, actor, eventId);
    const now = new Date().toISOString();
    const count = db.prepare(`SELECT count(*) AS total FROM companion_invites WHERE event_id = ? AND sender_id = ?
      AND redeemed_by IS NULL AND closed_at IS NULL AND expires_at > ?`).get(eventId, actor.personId, now);
    if (Number(count?.total) >= 5) throw new ApiError(429, 'INVITE_LIMIT', 'Ya tienes cinco invitaciones vigentes');
    const code = opaqueCode('DPC_');
    const expiresAt = new Date(Date.now() + 15 * 60_000).toISOString();
    db.prepare('INSERT INTO companion_invites (id, event_id, sender_id, code_hash, expires_at) VALUES (?, ?, ?, ?, ?)')
      .run(randomUUID(), eventId, actor.personId, hash(code), expiresAt);
    return { code, expiresAt };
  });
}

export function acceptCompanion(db: Db, actor: Session, eventId: string, code: string) {
  return atomic(db, () => {
    requireMembership(db, actor, eventId);
    const invite = db.prepare(`SELECT id, sender_id, redeemed_by, expires_at, closed_at FROM companion_invites
      WHERE event_id = ? AND code_hash = ?`).get(eventId, hash(code));
    if (!invite || invite.sender_id === actor.personId || invite.closed_at) {
      throw new ApiError(404, 'INVITE_NOT_FOUND', 'La invitación no está disponible');
    }
    if (invite.redeemed_by === actor.personId) return { id: invite.id };
    if (invite.redeemed_by || String(invite.expires_at) <= new Date().toISOString()) {
      throw new ApiError(404, 'INVITE_NOT_FOUND', 'La invitación no está disponible');
    }
    const sender = String(invite.sender_id);
    requireMembership(db, { personId: sender, role: 'ATTENDEE' }, eventId);
    const existing = db.prepare(`SELECT id FROM companion_invites WHERE event_id = ? AND closed_at IS NULL
      AND ((sender_id = ? AND redeemed_by = ?) OR (sender_id = ? AND redeemed_by = ?))`)
      .get(eventId, sender, actor.personId, actor.personId, sender);
    if (existing) throw new ApiError(409, 'COMPANION_EXISTS', 'El vínculo ya existe');
    db.prepare('UPDATE companion_invites SET redeemed_by = ? WHERE id = ?').run(actor.personId, invite.id);
    return { id: invite.id };
  });
}

export function companions(db: Db, eventId: string, personId: string, offset: number) {
  return { companions: db.prepare(`SELECT c.id, p.name FROM companion_invites c
    JOIN people p ON p.id = CASE WHEN c.sender_id = ? THEN c.redeemed_by ELSE c.sender_id END
    JOIN event_memberships m ON m.event_id = c.event_id AND m.person_id = p.id AND m.role = 'ATTENDEE'
    WHERE c.event_id = ? AND c.closed_at IS NULL AND (c.sender_id = ? OR c.redeemed_by = ?)
    ORDER BY p.name, c.id LIMIT 25 OFFSET ?`).all(personId, eventId, personId, personId, offset) };
}

export function closeCompanion(db: Db, actor: Session, eventId: string, id: string) {
  return atomic(db, () => {
    requireMembership(db, actor, eventId);
    if (!db.prepare(`UPDATE companion_invites SET closed_at = coalesce(closed_at, ?)
      WHERE id = ? AND event_id = ? AND (sender_id = ? OR redeemed_by = ?)`)
      .run(new Date().toISOString(), id, eventId, actor.personId, actor.personId).changes) {
      throw new ApiError(404, 'COMPANION_NOT_FOUND', 'El vínculo no está disponible');
    }
    return { closed: true };
  });
}

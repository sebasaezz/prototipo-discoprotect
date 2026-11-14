import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import type { Db } from './db.js';
import { ApiError } from './errors.js';
import type { Pins } from './config.js';

export type Role = 'ATTENDEE' | 'STAFF' | 'ORGANIZER';
export type Session = { personId: string; role: Role };

const durationMs = 8 * 60 * 60 * 1000;
const digest = (value: string) => createHash('sha256').update(value).digest();

const roles: Record<string, Role> = Object.assign(Object.create(null), {
  antonia: 'ATTENDEE', martin: 'ATTENDEE', sofia: 'ATTENDEE',
  guardia: 'STAFF', organizador: 'ORGANIZER',
});

export function issueSession(db: Db, pins: Pins, account: string, pin?: string) {
  const person = db.prepare('SELECT id FROM people WHERE demo_key = ?').get(account) as { id: string } | undefined;
  const role = roles[account];
  if (!person || !role) throw new ApiError(401, 'INVALID_CREDENTIALS', 'Cuenta o PIN de demostración incorrectos');
  if (role !== 'ATTENDEE') {
    const expected = pins[role];
    if (!expected || !pin || !timingSafeEqual(digest(expected), digest(pin))) {
      throw new ApiError(401, 'INVALID_CREDENTIALS', 'Cuenta o PIN de demostración incorrectos');
    }
  }
  const token = randomBytes(32).toString('base64url');
  const now = Date.now();
  db.prepare('DELETE FROM demo_sessions WHERE expires_at <= ?').run(now);
  db.prepare('INSERT INTO demo_sessions VALUES (?, ?, ?, ?)').run(digest(token).toString('hex'), person.id, role, now + durationMs);
  return { token, role, expiresAt: new Date(now + durationMs).toISOString() };
}

export function authenticate(db: Db, authorization: string | undefined): Session {
  const token = /^Bearer ([\w-]{43})$/i.exec(authorization ?? '')?.[1];
  if (token) {
    const session = db.prepare(`SELECT person_id AS personId, role FROM demo_sessions
      WHERE token_hash = ? AND expires_at > ?`).get(digest(token).toString('hex'), Date.now()) as Session | undefined;
    if (session) return session;
  }
  throw new ApiError(401, 'UNAUTHENTICATED', 'Inicia una sesión de demostración');
}

export function requireRole(session: Session, role: Role) {
  if (session.role !== role) throw new ApiError(403, 'FORBIDDEN', 'La sesión no tiene permiso para realizar esta operación');
}

export function requireMembership(db: Db, session: Session, eventId: string) {
  const allowed = db.prepare('SELECT 1 FROM event_memberships WHERE person_id = ? AND event_id = ? AND role = ?')
    .get(session.personId, eventId, session.role);
  if (!allowed) throw new ApiError(403, 'EVENT_FORBIDDEN', 'La sesión no está autorizada para este evento');
}

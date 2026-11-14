import { randomUUID } from 'node:crypto';
import { z } from 'zod';
import { atomic, type Db } from './db.js';
import { ApiError } from './errors.js';

const field = z.string().trim().min(1).max(160);
const items = z.array(field).max(30);
const bloodType = z.enum(['A+', 'A−', 'B+', 'B−', 'AB+', 'AB−', 'O+', 'O−']).nullable();
export const issuance = z.strictObject({ account: field.max(64), pin: z.string().max(128).optional() });
export const version = z.strictObject({ version: z.number().int().positive().max(Number.MAX_SAFE_INTEGER - 1) });
export const card = version.extend({ bloodType, allergies: items, conditions: items, medications: items });
export const contact = z.strictObject({ name: field, relationship: field, phone: z.string().trim().min(3).max(32).regex(/^[+0-9 ()-]+$/).refine(value => /\d/.test(value)) });
export const contactWrite = version.extend(contact.shape);
export const contactId = z.strictObject({ id: field });
export const eventId = z.strictObject({ eventId: field });
export const nfcQuery = eventId.extend({ token: z.string().regex(/^[0-9a-f]{32}$/i).transform(value => value.toUpperCase()) });
export const eventEdit = z.strictObject({ name: field, venue: field });

type CardRow = { id: string; blood_type: string | null; allergies: string; conditions: string; medications: string; updated_at: string; version: number };
type Contact = z.infer<typeof contact>;
type ContactRow = Contact & { id: string };

export function person(db: Db, id: string) {
  return db.prepare('SELECT id, demo_key AS account, name, age, created_at AS createdAt, updated_at AS updatedAt FROM people WHERE id = ?').get(id);
}

export function emergencyCard(db: Db, id: string) {
  const row = db.prepare('SELECT * FROM emergency_cards WHERE person_id = ?').get(id) as CardRow | undefined;
  if (!row) throw new ApiError(404, 'CARD_NOT_FOUND', 'No existe una ficha para esta persona');
  return {
    id: row.id, bloodType: row.blood_type, allergies: JSON.parse(row.allergies) as string[],
    conditions: JSON.parse(row.conditions) as string[], medications: JSON.parse(row.medications) as string[],
    updatedAt: row.updated_at, version: row.version,
  };
}

export function contacts(db: Db, id: string) {
  return db.prepare('SELECT id, name, relationship, phone FROM emergency_contacts WHERE person_id = ? ORDER BY id').all(id) as ContactRow[];
}

function mutateCard<T>(db: Db, id: string, version: number, work: () => T) {
  return atomic(db, () => {
    const current = emergencyCard(db, id);
    if (current.version !== version) {
      throw new ApiError(409, 'VERSION_CONFLICT', 'Vuelve a consultar la ficha y aplica tus cambios', { currentVersion: current.version });
    }
    db.prepare('UPDATE emergency_cards SET version = version + 1, updated_at = ? WHERE person_id = ?')
      .run(new Date().toISOString(), id);
    return work();
  });
}

export function replaceCard(db: Db, id: string, value: z.infer<typeof card>) {
  return mutateCard(db, id, value.version, () => {
    db.prepare('UPDATE emergency_cards SET blood_type = ?, allergies = ?, conditions = ?, medications = ? WHERE person_id = ?')
      .run(value.bloodType, JSON.stringify(value.allergies), JSON.stringify(value.conditions), JSON.stringify(value.medications), id);
    return emergencyCard(db, id);
  });
}

export function writeContact(db: Db, owner: string, value: z.infer<typeof contactWrite>, id?: string) {
  return mutateCard(db, owner, value.version, () => {
    if (id) {
      const updated = db.prepare('UPDATE emergency_contacts SET name = ?, relationship = ?, phone = ? WHERE person_id = ? AND id = ?')
        .run(value.name, value.relationship, value.phone, owner, id);
      if (!updated.changes) throw contactNotFound();
    } else {
      id = randomUUID();
      db.prepare('INSERT INTO emergency_contacts VALUES (?, ?, ?, ?, ?)').run(id, owner, value.name, value.relationship, value.phone);
    }
    return { contact: { id, name: value.name, relationship: value.relationship, phone: value.phone }, cardVersion: value.version + 1 };
  });
}

export function removeContact(db: Db, owner: string, id: string, version: number) {
  return mutateCard(db, owner, version, () => {
    const deleted = db.prepare('DELETE FROM emergency_contacts WHERE person_id = ? AND id = ?').run(owner, id);
    if (!deleted.changes) throw contactNotFound();
    return { cardVersion: version + 1 };
  });
}

function contactNotFound() {
  return new ApiError(404, 'CONTACT_NOT_FOUND', 'El contacto no existe en tu ficha');
}

export function ownCredential(db: Db, owner: string) {
  const credential = db.prepare('SELECT id, hex(token) AS token, active, created_at AS createdAt FROM nfc_credentials WHERE person_id = ?')
    .get(owner) as { id: string; token: string; active: number; createdAt: string } | undefined;
  if (!credential) throw new ApiError(404, 'NFC_NOT_FOUND', 'No existe una credencial asociada');
  return { ...credential, active: credential.active === 1 };
}

export function resolveNfc(db: Db, event: string, token: string) {
  // A deferred read transaction gives the returned version and contacts one snapshot.
  db.exec('BEGIN');
  try {
    const row = db.prepare(`SELECT c.person_id AS id FROM nfc_credentials c
      JOIN event_memberships m ON m.person_id = c.person_id
      WHERE c.token = ? AND c.active = 1 AND m.event_id = ? AND m.role = 'ATTENDEE'`)
      .get(Buffer.from(token, 'hex'), event) as { id: string } | undefined;
    if (!row) throw new ApiError(404, 'NFC_NOT_FOUND', 'No hay una ficha disponible para esta credencial en el evento');
    const result = { person: person(db, row.id), card: emergencyCard(db, row.id), contacts: contacts(db, row.id) };
    db.exec('COMMIT');
    return result;
  } catch (error) {
    db.exec('ROLLBACK');
    throw error;
  }
}

const eventFields = 'e.id, e.name, e.venue, e.starts_at AS startsAt, e.ends_at AS endsAt, e.status, m.role';

export function events(db: Db, personId: string) {
  return db.prepare(`SELECT ${eventFields} FROM events e JOIN event_memberships m ON m.event_id = e.id
    WHERE m.person_id = ? ORDER BY e.starts_at, e.id`).all(personId);
}

export function updateEvent(db: Db, id: string, owner: string, value: z.infer<typeof eventEdit>) {
  db.prepare('UPDATE events SET name = ?, venue = ? WHERE id = ?').run(value.name, value.venue, id);
  return db.prepare(`SELECT ${eventFields} FROM events e JOIN event_memberships m ON m.event_id = e.id
    WHERE e.id = ? AND m.person_id = ?`).get(id, owner);
}

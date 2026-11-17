import { createHash, randomUUID } from 'node:crypto';
import { closeSync, fsyncSync, mkdirSync, openSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { z } from 'zod';
import type { Session } from './auth.js';
import { atomic, type Db } from './db.js';
import { ApiError } from './errors.js';

export const purgeTarget = z.strictObject({ incidentId: z.uuid().optional() });
export const purgeInput = purgeTarget.extend({
  operationId: z.uuid().toLowerCase(), selectionHash: z.string().regex(/^[a-f0-9]{64}$/),
  confirmation: z.literal('ELIMINAR'),
});
const digest = (value: string | Buffer) => createHash('sha256').update(value).digest('hex');
const enabledFor = (db: Db, eventId: string, enabled: boolean) => enabled && eventId === 'demo-festival' && Boolean(db.location());

function selection(db: Db, eventId: string, id?: string) {
  return db.prepare(`SELECT id, version, requester_person_id AS requesterId, client_request_id AS requestId
    FROM incidents WHERE event_id = ? AND (? IS NULL OR id = ?) ORDER BY id`)
    .all(eventId, id ?? null, id ?? null) as { id: string; version: number; requesterId: string; requestId: string }[];
}
const hash = (rows: ReturnType<typeof selection>) => digest(JSON.stringify(rows));

export function previewPurge(db: Db, eventId: string, enabled: boolean, id?: string) {
  if (!enabledFor(db, eventId, enabled)) return { enabled: false, count: 0, selectionHash: null };
  const rows = selection(db, eventId, id);
  return { enabled: true, count: rows.length, selectionHash: hash(rows) };
}

function backup(db: Db, eventId: string, expected: string, id?: string) {
  const directory = join(dirname(db.location()!), 'demo-purge-backups');
  const backupName = `${randomUUID()}.sqlite`;
  const path = join(directory, backupName);
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  db.prepare('VACUUM INTO ?').run(path);
  const copy = new DatabaseSync(path, { readOnly: true });
  try {
    const integrity = copy.prepare('PRAGMA integrity_check').all();
    if (integrity.length !== 1 || integrity[0].integrity_check !== 'ok' ||
        copy.prepare('PRAGMA foreign_key_check').get() || hash(selection(copy, eventId, id)) !== expected) {
      throw new Error('Invalid backup');
    }
  } finally {
    copy.close();
  }
  const fd = openSync(path, 'r+');
  try {
    fsyncSync(fd);
    return { backupName, backupSha256: digest(readFileSync(fd)) };
  } finally {
    closeSync(fd);
  }
}

export function purgeIncidents(db: Db, eventId: string, session: Session, enabled: boolean, input: z.infer<typeof purgeInput>) {
  if (!enabledFor(db, eventId, enabled)) throw new ApiError(403, 'PURGE_DISABLED', 'La eliminación definitiva de demo está desactivada');
  const { incidentId, operationId, selectionHash } = input;
  const previous = db.prepare(`SELECT event_id AS eventId, actor_person_id AS actor, target_id AS target,
    selection_hash AS selectionHash, count, backup_name AS backupName, backup_sha256 AS backupSha256
    FROM incident_purges WHERE operation_id = ?`).get(operationId);
  if (previous) {
    if (previous.eventId !== eventId || previous.actor !== session.personId ||
        previous.target !== (incidentId ?? null) || previous.selectionHash !== selectionHash) {
      throw new ApiError(409, 'REQUEST_CONFLICT', 'La clave de eliminación ya se usó con otros datos');
    }
    return { count: previous.count, backupName: previous.backupName, backupSha256: previous.backupSha256, replayed: true };
  }
  const changed = () => new ApiError(409, 'PURGE_SELECTION_CHANGED', 'Las solicitudes cambiaron. Vuelve a consultar la cantidad.');
  const dataVersion = () => db.prepare('PRAGMA data_version').get()?.data_version;
  const observed = dataVersion();
  const rows = selection(db, eventId, incidentId);
  if (!rows.length || hash(rows) !== selectionHash) throw changed();
  let receipt: ReturnType<typeof backup>;
  try {
    receipt = backup(db, eventId, selectionHash, incidentId);
  } catch {
    throw new ApiError(500, 'PURGE_BACKUP_FAILED', 'No se pudo verificar el respaldo. No se eliminaron solicitudes.');
  }
  return atomic(db, () => {
    if (dataVersion() !== observed || hash(selection(db, eventId, incidentId)) !== selectionHash) throw changed();
    db.prepare('INSERT INTO incident_purges VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)').run(
      operationId, eventId, session.personId, incidentId ?? null, selectionHash, rows.length,
      receipt.backupName, receipt.backupSha256, new Date().toISOString());
    const tombstone = db.prepare('INSERT INTO incident_tombstones VALUES (?, ?, ?, ?)');
    const remove = db.prepare('DELETE FROM incidents WHERE event_id = ? AND id = ?');
    for (const row of rows) {
      tombstone.run(row.id, row.requesterId, row.requestId, operationId);
      remove.run(eventId, row.id);
    }
    return { count: rows.length, ...receipt, replayed: false };
  });
}

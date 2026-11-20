import { randomUUID } from 'node:crypto';
import { closeSync, fsyncSync, mkdirSync, openSync, realpathSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { savePerrysStage } from './perrys-stage.mjs';

const input = process.argv[2];
if (!input?.endsWith('.sqlite')) throw new Error('Proporciona la ruta verificada de la base .sqlite existente');
const path = realpathSync(input);
if (!statSync(path).isFile()) throw new Error('La ruta debe apuntar a un archivo SQLite');

const db = new DatabaseSync(path);
try {
  db.exec('PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000');
  const directory = join(dirname(path), 'demo-catalog-backups');
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  const backupPath = join(directory, `perrys-stage-${randomUUID()}.sqlite`);
  db.prepare('VACUUM INTO ?').run(backupPath);
  const copy = new DatabaseSync(backupPath, { readOnly: true });
  try {
    if (copy.prepare('PRAGMA integrity_check').get()?.integrity_check !== 'ok' ||
        copy.prepare('PRAGMA foreign_key_check').get()) {
      throw new Error('El respaldo SQLite no superó las comprobaciones de integridad');
    }
  } finally {
    copy.close();
  }
  const fd = openSync(backupPath, 'r+');
  try {
    fsyncSync(fd);
  } finally {
    closeSync(fd);
  }
  console.log(JSON.stringify({ backupPath }));

  const change = savePerrysStage(db);
  const zone = db.prepare(`SELECT event_id, id, name, kind, description, reference, active
    FROM zones WHERE event_id = ? AND id = ? AND active = 1`).get('demo-festival', 'entrada-perrys-stage');
  if (!zone) throw new Error('No se pudo verificar la zona activa');
  console.log(JSON.stringify({ path, ...change, zone }));
} finally {
  db.close();
}

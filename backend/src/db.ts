import { mkdirSync, readdirSync, readFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { DatabaseSync } from 'node:sqlite';

export type Db = DatabaseSync;

export function openDb(path: string): Db {
  if (path !== ':memory:') mkdirSync(dirname(path), { recursive: true });
  const db = new DatabaseSync(path);
  try {
    db.exec('PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000; PRAGMA journal_mode = WAL');
    return db;
  } catch (error) {
    db.close();
    throw error;
  }
}

export function atomic<T>(db: Db, work: () => T): T {
  db.exec('BEGIN IMMEDIATE');
  try {
    const result = work();
    db.exec('COMMIT');
    return result;
  } catch (error) {
    db.exec('ROLLBACK');
    throw error;
  }
}

const migrations = new URL('../../migrations/', import.meta.url);

export function migrate(db: Db) {
  atomic(db, () => {
    db.exec('CREATE TABLE IF NOT EXISTS schema_migrations (filename TEXT PRIMARY KEY, applied_at TEXT NOT NULL) STRICT');
    for (const filename of readdirSync(migrations).filter(name => /^\d{3}_[a-z_]+\.sql$/.test(name)).sort()) {
      if (db.prepare('SELECT 1 FROM schema_migrations WHERE filename = ?').get(filename)) continue;
      if (filename === '003_incident_lifecycle.sql') {
        const reference = db.prepare(`SELECT 1 FROM sqlite_schema AS s, pragma_foreign_key_list(s.name) AS fk
          WHERE s.type = 'table' AND lower(fk."table") = 'incidents' LIMIT 1`).get();
        if (reference) throw new Error('La tabla incidents tiene referencias entrantes inesperadas');
      }
      db.exec(readFileSync(new URL(filename, migrations), 'utf8'));
      if (db.prepare('PRAGMA foreign_key_check').all().length) {
        throw new Error(`La migración ${filename} dejó claves foráneas inconsistentes`);
      }
      db.prepare('INSERT INTO schema_migrations VALUES (?, ?)').run(filename, new Date().toISOString());
    }
  });
}

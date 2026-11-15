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
      db.exec(readFileSync(new URL(filename, migrations), 'utf8'));
      db.prepare('INSERT INTO schema_migrations VALUES (?, ?)').run(filename, new Date().toISOString());
    }
  });
}

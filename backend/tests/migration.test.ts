import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { migrate, openDb } from '../src/db.js';
import { seed } from '../src/seed.js';

function legacy(t: import('node:test').TestContext) {
  const db = openDb(':memory:');
  t.after(() => db.close());
  db.exec('CREATE TABLE schema_migrations (filename TEXT PRIMARY KEY, applied_at TEXT NOT NULL) STRICT');
  for (const name of ['001_initial.sql', '002_incidents.sql']) {
    db.exec(readFileSync(new URL(`../../migrations/${name}`, import.meta.url), 'utf8'));
    db.prepare("INSERT INTO schema_migrations VALUES (?, 'existing')").run(name);
  }
  seed(db);
  db.exec(`DELETE FROM people WHERE demo_key = 'guardia2';
    UPDATE emergency_cards SET version = 7 WHERE person_id = 'demo-antonia';
    INSERT INTO demo_sessions VALUES ('test-hash', 'demo-antonia', 'ATTENDEE', 9999999999999);
    INSERT INTO incidents VALUES
      ('f2dc1804-c68e-4d3e-ae18-c7c7a688ba72', 'demo-festival', 'demo-antonia', 'escenario', 'Salida', 'OPEN',
       'bfcbc8ae-dc8c-4f43-b073-4dddc6c3a689', '2026-11-15T01:00:00.000Z', '2026-11-15T01:00:00.000Z'),
      ('c7a19bfd-c1fd-4ab7-b6d8-9a92697cbe09', 'demo-festival', 'demo-antonia', NULL, NULL, 'CANCELLED',
       'f42c5d31-927c-49f5-89d4-8d5e55e083da', '2026-11-15T02:00:00.000Z', '2026-11-15T02:10:00.000Z');`);
  return db;
}

const legacyFields = 'id, event_id, requester_person_id, zone_id, location_text, status, client_request_id, created_at, updated_at';

test('003 preserves H5 data, indexes and keys and can be run repeatedly', t => {
  const db = legacy(t);
  const rows = db.prepare(`SELECT ${legacyFields} FROM incidents ORDER BY id`).all();
  const accounts = ['demo_sessions', 'emergency_cards', 'emergency_contacts', 'nfc_credentials'];
  const snapshot = () => accounts.map(table => db.prepare(`SELECT * FROM ${table} ORDER BY 1`).all());
  const before = snapshot();
  const indexes = db.prepare("SELECT name, sql FROM sqlite_schema WHERE type = 'index' AND tbl_name = 'incidents' ORDER BY name");
  const beforeIndexes = indexes.all();
  migrate(db);
  migrate(db);
  seed(db);
  assert.deepEqual(db.prepare(`SELECT ${legacyFields} FROM incidents ORDER BY id`).all(), rows);
  assert.deepEqual(snapshot(), before);
  assert.equal(db.prepare("SELECT count(*) AS count FROM people WHERE demo_key = 'guardia2'").get()?.count, 1);
  assert.deepEqual(indexes.all(), beforeIndexes);
  assert.equal(db.prepare('SELECT count(*) AS count FROM incidents WHERE version = 1 AND assigned_staff_id IS NULL').get()?.count, 2);
  assert.equal(db.prepare('PRAGMA foreign_keys').get()?.foreign_keys, 1);
  assert.deepEqual(db.prepare('PRAGMA foreign_key_check').all(), []);
  assert.throws(() => db.exec("UPDATE incidents SET zone_id = 'missing'"), /FOREIGN KEY/);
  assert.throws(() => db.exec("UPDATE incidents SET status = 'RESOLVED'"), /CHECK/);
});

test('003 rolls back table replacement and migration record when foreign_key_check finds an orphan', t => {
  const db = legacy(t);
  db.exec(`PRAGMA foreign_keys = OFF;
    INSERT INTO emergency_contacts VALUES ('orphan', 'missing', 'X', 'X', '000');
    PRAGMA foreign_keys = ON;`);
  const schema = db.prepare('SELECT name, sql FROM sqlite_schema ORDER BY name');
  const beforeSchema = schema.all();
  const beforeRows = db.prepare('SELECT * FROM incidents ORDER BY id').all();
  const beforeMigrations = db.prepare('SELECT * FROM schema_migrations ORDER BY filename').all();
  assert.throws(() => migrate(db), /003_incident_lifecycle.sql dejó claves foráneas inconsistentes/);
  assert.deepEqual(schema.all(), beforeSchema);
  assert.deepEqual(db.prepare('SELECT * FROM incidents ORDER BY id').all(), beforeRows);
  assert.deepEqual(db.prepare('SELECT * FROM schema_migrations ORDER BY filename').all(), beforeMigrations);
});

test('003 refuses unexpected references to H5 incidents before dropping the table', t => {
  const db = legacy(t);
  db.exec('CREATE TABLE external_reference (id TEXT REFERENCES incidents(id) ON DELETE CASCADE)');
  assert.throws(() => migrate(db), /referencias entrantes/);
  assert.equal(db.prepare('SELECT count(*) AS count FROM incidents').get()?.count, 2);
});

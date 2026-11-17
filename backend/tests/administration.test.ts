import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { test, type TestContext } from 'node:test';
import { createApp } from '../src/app.js';
import { migrate, openDb } from '../src/db.js';
import { seed } from '../src/seed.js';

const event = 'demo-festival';
const queue = (id = event) => `/api/v1/events/${id}/incidents`;
const pins = { STAFF: 'test-only-guard', STAFF_2: 'test-only-other', ORGANIZER: 'test-only-organizer' };
const operation = (version = 1) => ({ version, operationId: randomUUID() });

async function fixture(t: TestContext, disk = false, enabled = disk) {
  const dir = disk ? mkdtempSync(join(tmpdir(), 'h73-')) : null;
  const db = openDb(dir ? join(dir, 'scratch.sqlite') : ':memory:');
  migrate(db);
  seed(db);
  const app = createApp(db, pins, false, false, undefined, enabled);
  t.after(async () => {
    await app.close();
    db.close();
    if (dir) rmSync(dir, { recursive: true, force: true });
  });
  const login = async (account: string, pin?: string) => {
    const response = await app.inject({ method: 'POST', url: '/api/v1/demo/sessions', payload: { account, pin } });
    assert.equal(response.statusCode, 201, response.body);
    return { authorization: `Bearer ${response.json().token}` };
  };
  const attendee = await login('antonia');
  const guard = await login('guardia', pins.STAFF);
  const other = await login('guardia2', pins.STAFF_2);
  const organizer = await login('organizador', pins.ORGANIZER);
  const post = (url: string, headers: typeof guard, payload?: object) => app.inject({ method: 'POST', url, headers, payload });
  const get = (url: string, headers: typeof guard) => app.inject({ url, headers });
  const create = async (eventId = event) => {
    const input = { mode: 'CHAT', clientRequestId: randomUUID(),
      firstMessage: { clientMessageId: randomUUID(), text: 'Solicitud ficticia.' } };
    const created = await post(queue(eventId), attendee, input);
    assert.equal(created.statusCode, 201, created.body);
    const { id } = created.json().incident;
    return { id, input, url: `${queue(eventId)}/${id}`, own: `/api/v1/me/incidents/${id}` };
  };
  return { db, dir, post, get, create, attendee, guard, other, organizer };
}

test('H7.3 one guard across events, exact replays, cancellation and closure release the guard', async t => {
  const f = await fixture(t);
  f.db.exec(`INSERT INTO events SELECT 'otro', name, venue, starts_at, ends_at, status FROM events WHERE id = 'demo-festival';
    INSERT INTO event_memberships VALUES ('other-attendee', 'otro', 'demo-antonia', 'ATTENDEE'),
      ('other-guard', 'otro', 'demo-guardia', 'STAFF');`);
  const incidents = [await f.create(), await f.create('otro')];
  const commands = incidents.map(() => operation());
  const claims = await Promise.all(incidents.map((entry, i) => f.post(entry.url + '/claim', f.guard, commands[i])));
  const winner = claims.findIndex(response => response.statusCode === 200);
  assert.notEqual(winner, -1);
  const active = incidents[winner], waiting = incidents[1 - winner];
  assert.equal(claims[1 - winner].statusCode, 409);
  assert.equal(claims[1 - winner].json().error.code, 'GUARD_ALREADY_BUSY');
  const cross = await f.get(winner === 0 ? queue('otro') : queue(), f.guard);
  assert.equal(cross.json().guardBusy, true);
  assert.equal(cross.json().activeIncident.id, active.id);
  assert.equal((await f.post(active.url + '/claim', f.guard, commands[winner])).json().operation.replayed, true);
  const cancelled = await f.post(active.own + '/cancel', f.attendee);
  assert.equal(cancelled.json().status, 'CANCELLED');
  assert.ok(cancelled.json().assignedAt);
  assert.equal((await f.post(active.own + '/cancel', f.attendee)).json().version, 3);
  assert.equal((await f.get(active.own + '/conversation', f.attendee)).json().conversation.control, 'CLOSED');
  const audit = await f.get(active.url + '/dispatch', f.guard);
  assert.deepEqual(audit.json().transitions.map((entry: { toStatus: string }) => entry.toStatus), ['ASSIGNED', 'CANCELLED']);
  assert.equal((await f.post(waiting.url + '/claim', f.guard, commands[1 - winner])).statusCode, 200);
  assert.equal((await f.post(waiting.url + '/close', f.guard, { ...operation(2), resolutionNote: '  ' })).statusCode, 400);
  const close = { ...operation(2), resolutionNote: 'Solicitud atendida en terreno' };
  const closed = await f.post(waiting.url + '/close', f.guard, close);
  assert.equal(closed.statusCode, 200, closed.body);
  assert.equal(closed.json().incident.status, 'CLOSED');
  assert.equal(closed.json().incident.startedAt, null);
  assert.equal((await f.post(waiting.url + '/close', f.guard, close)).json().operation.replayed, true);
  assert.equal((await f.get(waiting.own + '/conversation', f.attendee)).json().conversation.control, 'CLOSED');
  assert.equal((await f.get(queue(), f.guard)).json().guardBusy, false);
});

test('H7.3 closes in-progress and audits organizer withdrawal of an assigned incident', async t => {
  const f = await fixture(t);
  const first = await f.create();
  assert.equal((await f.post(first.url + '/claim', f.guard, operation())).statusCode, 200);
  assert.equal((await f.post(first.url + '/close', f.other, { ...operation(2), resolutionNote: 'No autorizado' })).statusCode, 403);
  assert.equal((await f.post(first.url + '/start', f.guard, operation(2))).statusCode, 200);
  assert.equal((await f.post(first.url + '/close', f.guard, { ...operation(3), resolutionNote: 'Verificado' })).json().incident.status, 'CLOSED');
  const second = await f.create();
  assert.equal((await f.post(second.url + '/claim', f.guard, operation())).statusCode, 200);
  const withdraw = { ...operation(2), resolutionNote: 'Registro de entrenamiento' };
  assert.equal((await f.post(second.url + '/withdraw', f.guard, withdraw)).statusCode, 403);
  assert.equal((await f.post(second.url + '/withdraw', f.organizer, withdraw)).json().incident.status, 'WITHDRAWN');
  const transitions = (await f.get(second.url + '/dispatch', f.organizer)).json().transitions;
  assert.equal(transitions.at(-1).toStatus, 'WITHDRAWN');
  assert.ok(transitions.at(-1).actorName);
  assert.equal((await f.get(queue(), f.guard)).json().guardBusy, false);
});

test('H7.3 verified demo purge is scoped, counted, idempotent and leaves tombstones', async t => {
  const f = await fixture(t, true);
  const purge = queue() + '/demo-purge';
  const item = await f.create();
  const other = await f.create();
  assert.equal((await f.post(item.url + '/claim', f.guard, operation())).statusCode, 200);
  assert.equal((await f.get(purge, f.guard)).statusCode, 403);
  const single = (await f.get(purge + '?incidentId=' + item.id, f.organizer)).json();
  assert.equal(single.count, 1);
  const command = { operationId: randomUUID(), selectionHash: single.selectionHash, incidentId: item.id, confirmation: 'ELIMINAR' };
  assert.equal((await f.post(purge, f.organizer, { ...command, confirmation: 'NO' })).statusCode, 400);
  assert.equal((await f.post(purge, f.organizer, { ...command, selectionHash: '0'.repeat(64) })).statusCode, 409);
  const completed = await f.post(purge, f.organizer, command);
  assert.equal(completed.statusCode, 200, completed.body);
  const receipt = completed.json();
  const backup = join(f.dir!, 'demo-purge-backups', receipt.backupName);
  assert.equal(createHash('sha256').update(readFileSync(backup)).digest('hex'), receipt.backupSha256);
  const copy = new DatabaseSync(backup, { readOnly: true });
  assert.ok(copy.prepare('SELECT id FROM incidents WHERE id = ?').get(item.id));
  copy.close();
  assert.equal((await f.post(purge, f.organizer, command)).json().replayed, true);
  assert.equal((await f.post(queue(), f.attendee, item.input)).json().error.code, 'REQUEST_PURGED');
  assert.equal((await f.get(item.own + '/conversation', f.attendee)).statusCode, 404);
  assert.equal((await f.get(other.own, f.attendee)).statusCode, 200);
  await f.create();
  const bulk = (await f.get(purge, f.organizer)).json();
  assert.equal(bulk.count, 2);
  const deleted = await f.post(purge, f.organizer, {
    operationId: randomUUID(), selectionHash: bulk.selectionHash, confirmation: 'ELIMINAR',
  });
  assert.equal(deleted.json().count, 2);
  for (const table of ['incidents', 'incident_messages', 'incident_chat_ai_runs', 'incident_triage', 'incident_conversations',
    'incident_transitions', 'incident_dispatch_reviews']) {
    assert.equal(f.db.prepare(`SELECT count(*) AS n FROM ${table}`).get()?.n, 0);
  }
  assert.ok(f.db.prepare('SELECT 1 FROM zones').get());
  assert.ok(f.db.prepare('SELECT 1 FROM nfc_credentials').get());
  assert.ok(f.db.prepare('SELECT 1 FROM events').get());
  assert.ok(f.db.prepare('SELECT 1 FROM people').get());
});

test('H7.3 disabled purge or a failed backup leaves the incident intact', async t => {
  const off = await fixture(t, true, false);
  const route = queue() + '/demo-purge';
  assert.equal((await off.get(route, off.organizer)).json().enabled, false);
  assert.equal((await off.post(route, off.organizer, {
    operationId: randomUUID(), selectionHash: '0'.repeat(64), confirmation: 'ELIMINAR',
  })).json().error.code, 'PURGE_DISABLED');
  const f = await fixture(t, true);
  const entry = await f.create();
  writeFileSync(join(f.dir!, 'demo-purge-backups'), 'simulated I/O failure');
  const preview = (await f.get(route, f.organizer)).json();
  const denied = await f.post(route, f.organizer, {
    operationId: randomUUID(), selectionHash: preview.selectionHash, confirmation: 'ELIMINAR',
  });
  assert.equal(denied.json().error.code, 'PURGE_BACKUP_FAILED');
  assert.equal((await f.get(entry.own, f.attendee)).statusCode, 200);
});

test('H7.3 migration detects duplicate active assignments without rewriting rows', () => {
  const db = openDb(':memory:');
  try {
    const directory = new URL('../../migrations/', import.meta.url);
    db.exec('CREATE TABLE schema_migrations (filename TEXT PRIMARY KEY, applied_at TEXT NOT NULL) STRICT');
    for (const name of readdirSync(directory).filter(name => /^00[1-7]_/.test(name)).sort()) {
      db.exec(readFileSync(new URL(name, directory), 'utf8'));
      db.prepare('INSERT INTO schema_migrations VALUES (?, ?)').run(name, 'test');
    }
    seed(db);
    const insert = db.prepare(`INSERT INTO incidents(id,event_id,requester_person_id,status,client_request_id,created_at,updated_at,
      assigned_staff_id,assigned_at) VALUES (?,'demo-festival','demo-antonia','ASSIGNED',?, ?,?,'demo-guardia',?)`);
    const time = new Date().toISOString();
    for (let i = 0; i < 2; i++) insert.run(randomUUID(), randomUUID(), time, time, time);
    const rows = db.prepare('SELECT * FROM incidents ORDER BY id').all();
    assert.throws(() => migrate(db), /varias asignaciones activas/);
    assert.deepEqual(db.prepare('SELECT * FROM incidents ORDER BY id').all(), rows);
    assert.equal(db.prepare('PRAGMA foreign_keys').get()?.foreign_keys, 1);
    db.prepare('UPDATE incidents SET status = ?, assigned_staff_id = NULL, assigned_at = NULL WHERE id = ?').run('OPEN', rows[0].id);
    db.prepare("INSERT INTO incident_triage(incident_id,status,fallback_reason,queued_at) VALUES (?,'MANUAL','LEGACY',0)").run(rows[0].id);
    migrate(db);
    assert.ok(db.prepare('SELECT 1 FROM incident_triage WHERE incident_id = ?').get(rows[0].id));
    assert.throws(() => insert.run(randomUUID(), randomUUID(), time, time, time), /UNIQUE/);
  } finally { db.close(); }
});

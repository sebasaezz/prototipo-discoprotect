import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { createApp } from '../src/app.js';
import { migrate, openDb } from '../src/db.js';
import { DEMO_NFC, resetDemo, seed } from '../src/seed.js';

const pins = { STAFF: 'test-staff-only', ORGANIZER: 'test-organizer-only' };
const eventId = 'demo-festival';
const prefix = '/api/v1';

async function fixture(t: import('node:test').TestContext, path = ':memory:') {
  const db = openDb(path);
  migrate(db);
  seed(db);
  const app = createApp(db, pins);
  t.after(async () => { await app.close(); db.close(); });
  const login = async (account = 'antonia', pin?: string) => {
    const response = await app.inject({ method: 'POST', url: prefix + '/demo/sessions', payload: { account, pin } });
    assert.equal(response.statusCode, 201, response.body);
    return { authorization: `Bearer ${response.json().token}` };
  };
  return { app, db, login };
}

test('health, foreign keys, migrations and repeatable seed', async t => {
  const { app, db } = await fixture(t);
  migrate(db);
  seed(db);
  assert.equal(db.prepare('SELECT count(*) AS n FROM schema_migrations').get()?.n, 1);
  assert.equal(db.prepare('SELECT count(*) AS n FROM people').get()?.n, 5);
  assert.equal(db.prepare('SELECT count(*) AS n FROM emergency_contacts').get()?.n, 3);
  assert.equal(db.prepare('SELECT count(*) AS n FROM nfc_credentials WHERE length(token) = 16').get()?.n, 3);
  assert.throws(() => db.prepare("INSERT INTO emergency_contacts VALUES ('bad', 'absent', 'X', 'X', '000')").run());
  const response = await app.inject({ url: prefix + '/health' });
  assert.equal(response.statusCode, 200);
  assert.deepEqual(response.json(), { status: 'ok', database: 'ok' });
});

test('account, own record, event and credential contracts', async t => {
  const { app, login } = await fixture(t);
  const headers = await login();
  const me = await app.inject({ url: prefix + '/me', headers });
  assert.equal(me.json().person.name, 'Antonia Reyes');
  assert.equal(me.json().role, 'ATTENDEE');
  const card = await app.inject({ url: prefix + '/me/emergency-card', headers });
  assert.deepEqual(card.json().allergies, ['Penicilina']);
  const events = await app.inject({ url: prefix + '/events', headers });
  assert.deepEqual(events.json().events.map((event: { id: string }) => event.id), [eventId]);
  const nfc = await app.inject({ url: prefix + '/me/nfc-credential', headers });
  assert.equal(nfc.json().token, DEMO_NFC);
  assert.equal(nfc.json().active, true);
});

test('updates persist across a server restart and seed preserves changes', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'discoprotect-'));
  const path = join(dir, 'test.sqlite');
  const open = () => {
    const db = openDb(path);
    migrate(db);
    seed(db);
    return { db, app: createApp(db, pins) };
  };
  let current = open();
  t.after(async () => {
    await current.app.close();
    current.db.close();
    rmSync(dir, { recursive: true });
  });
  const login = await current.app.inject({ method: 'POST', url: prefix + '/demo/sessions', payload: { account: 'antonia' } });
  const headers = { authorization: 'Bearer ' + login.json().token };
  const payload = { version: 1, bloodType: 'O+', allergies: ['Látex'], conditions: ['Asma'], medications: ['Inhalador ficticio'] };
  const edited = await current.app.inject({ method: 'PUT', url: prefix + '/me/emergency-card', headers, payload });
  assert.equal(edited.statusCode, 200, edited.body);
  assert.equal(edited.json().version, 2);
  const stale = await current.app.inject({ method: 'PUT', url: prefix + '/me/emergency-card', headers, payload });
  assert.equal(stale.statusCode, 409);
  assert.equal(stale.json().error.details.currentVersion, 2);
  await current.app.close();
  current.db.close();
  current = open();
  assert.deepEqual((await current.app.inject({ url: prefix + '/me/emergency-card', headers })).json(), edited.json());
  const guard = await current.app.inject({ method: 'POST', url: prefix + '/demo/sessions', payload: { account: 'guardia', pin: pins.STAFF } });
  const resolved = await current.app.inject({ method: 'POST', url: prefix + '/nfc/resolve',
    headers: { authorization: 'Bearer ' + guard.json().token }, payload: { token: DEMO_NFC.toLowerCase(), eventId } });
  assert.equal(resolved.statusCode, 200);
  assert.deepEqual(resolved.json().card, edited.json());
  assert.equal(resolved.json().contacts.length, 1);
});

test('contact CRUD is owner scoped and uses the same record version', async t => {
  const { app, login, db } = await fixture(t);
  const headers = await login();
  const url = prefix + '/me/contacts';
  const payload = { name: 'Contacto Demo', relationship: 'Amistad', phone: '+56 9 0000 0004', version: 1 };
  const created = await app.inject({ method: 'POST', url, headers, payload });
  assert.equal(created.statusCode, 201);
  const id = created.json().contact.id;
  assert.equal(created.json().cardVersion, 2);
  assert.equal((await app.inject({ url, headers })).json().contacts.length, 2);
  const foreign = await app.inject({ method: 'PUT', url: `${url}/${id}`, headers: await login('martin'), payload });
  assert.equal(foreign.statusCode, 404);
  const stale = await app.inject({ method: 'PUT', url: `${url}/${id}`, headers, payload });
  assert.equal(stale.statusCode, 409);
  const edit = await app.inject({ method: 'PUT', url: `${url}/${id}`, headers, payload: { ...payload, name: 'Otro Demo', version: 2 } });
  assert.equal(edit.statusCode, 200);
  assert.equal(edit.json().contact.name, 'Otro Demo');
  const deleted = await app.inject({ method: 'DELETE', url: `${url}/${id}`, headers, payload: { version: 3 } });
  assert.equal(deleted.statusCode, 200);
  assert.equal(deleted.json().cardVersion, 4);
  const original = (await app.inject({ url, headers })).json().contacts[0];
  await app.inject({ method: 'DELETE', url: `${url}/${original.id}`, headers, payload: { version: 4 } });
  seed(db);
  assert.deepEqual((await app.inject({ url, headers })).json().contacts, []);
  resetDemo(db);
  assert.equal((await app.inject({ url, headers })).statusCode, 401);
  assert.deepEqual((await app.inject({ url, headers: await login() })).json().contacts, [original]);
});

test('NFC validation and authorization respect both event memberships', async t => {
  const { app, db, login } = await fixture(t);
  const headers = await login('guardia', pins.STAFF);
  const url = prefix + '/nfc/resolve';
  const payload = { token: DEMO_NFC, eventId };
  assert.equal((await app.inject({ method: 'POST', url, payload })).statusCode, 401);
  assert.equal((await app.inject({ method: 'POST', url, headers: await login(), payload })).statusCode, 403);
  assert.equal((await app.inject({ method: 'POST', url, headers: await login('organizador', pins.ORGANIZER), payload })).statusCode, 403);
  assert.equal((await app.inject({ method: 'POST', url, headers, payload: { ...payload, eventId: 'otro' } })).statusCode, 403);
  for (const token of ['abc', 'Z'.repeat(32), 'a'.repeat(33)]) {
    assert.equal((await app.inject({ method: 'POST', url, headers, payload: { ...payload, token } })).statusCode, 400);
  }
  assert.equal((await app.inject({ method: 'POST', url, headers, payload: { ...payload, token: 'ff'.repeat(16) } })).statusCode, 404);
  assert.equal((await app.inject({ method: 'POST', url, headers, payload })).statusCode, 200);
  db.exec("UPDATE nfc_credentials SET active = 0 WHERE person_id = 'demo-antonia'");
  assert.equal((await app.inject({ method: 'POST', url, headers, payload })).statusCode, 404);
  db.exec("UPDATE nfc_credentials SET active = 1; DELETE FROM event_memberships WHERE person_id = 'demo-antonia'");
  assert.equal((await app.inject({ method: 'POST', url, headers, payload })).statusCode, 404);
});

test('credentials fail closed, expiration and organizer event scope', async t => {
  const { app, db, login } = await fixture(t);
  const sessionUrl = prefix + '/demo/sessions';
  for (const payload of [{ account: 'guardia' }, { account: 'organizador', pin: pins.STAFF }, { account: 'ausente' }]) {
    assert.equal((await app.inject({ method: 'POST', url: sessionUrl, payload })).statusCode, 401);
  }
  assert.equal((await app.inject({ method: 'POST', url: sessionUrl, payload: { account: 'antonia', role: 'STAFF' } })).statusCode, 400);
  const headers = await login('organizador', pins.ORGANIZER);
  const payload = { name: 'Festival editado', venue: 'Recinto B' };
  assert.equal((await app.inject({ method: 'PATCH', url: prefix + '/events/otro', headers, payload })).statusCode, 403);
  const updated = await app.inject({ method: 'PATCH', url: prefix + '/events/' + eventId, headers, payload });
  assert.equal(updated.json().venue, 'Recinto B');
  assert.equal((await app.inject({ method: 'PATCH', url: prefix + '/events/' + eventId, headers: await login(), payload })).statusCode, 403);
  assert.equal((await app.inject({ url: prefix + '/me/emergency-card', headers })).statusCode, 403);
  const badCard = await app.inject({ method: 'PUT', url: prefix + '/me/emergency-card', headers: await login(),
    payload: { version: 1, bloodType: 'inválido', allergies: [' '], conditions: [], medications: [] } });
  assert.equal(badCard.statusCode, 400);
  assert.equal(badCard.json().error.code, 'INVALID_INPUT');
  db.exec('UPDATE demo_sessions SET expires_at = 0');
  assert.equal((await app.inject({ url: prefix + '/me', headers })).statusCode, 401);
});

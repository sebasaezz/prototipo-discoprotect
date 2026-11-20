import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { createApp } from '../src/app.js';
import { migrate, openDb } from '../src/db.js';
import { seed } from '../src/seed.js';

const root = '/api/v1/events/demo-festival';
const pins = { ORGANIZER: 'test-organizer', STAFF: 'test-staff' };
const zone = { name: 'Escenario alternativo', kind: 'STAGE', description: 'Sector de bandas', reference: 'Junto a la valla', active: true };
const venue = { name: 'Festival revisado', venue: 'Recinto norte', description: 'Dos sectores cubiertos',
  accessNotes: 'Entrada lateral B', responseNotes: 'Referencias visibles junto al escenario' };

async function login(app: ReturnType<typeof createApp>, account: string, pin?: string) {
  const reply = await app.inject({ method: 'POST', url: '/api/v1/demo/sessions', payload: { account, pin } });
  assert.equal(reply.statusCode, 201);
  return { authorization: 'Bearer ' + reply.json().token };
}

test('venue edits, stable zones, points and audit survive reopening Fastify and SQLite', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'discoprotect-venue-'));
  const path = join(dir, 'db.sqlite');
  let db = openDb(path);
  migrate(db);
  seed(db);
  let app = createApp(db, pins);
  try {
    const organizer = await login(app, 'organizador', pins.ORGANIZER);
    const attendee = await login(app, 'antonia');
    const staff = await login(app, 'guardia', pins.STAFF);
    const saveVenue = await app.inject({ method: 'PATCH', url: root, headers: organizer, payload: { ...venue, version: 1 } });
    assert.equal(saveVenue.statusCode, 200, saveVenue.body);
    assert.equal(saveVenue.json().version, 2);
    const createZone = await app.inject({ method: 'POST', url: root + '/zones', headers: organizer, payload: { ...zone, version: 2 } });
    assert.equal(createZone.statusCode, 201, createZone.body);
    const { id } = createZone.json().zone;
    assert.ok(id);
    assert.equal((await app.inject({ url: root + '/zones', headers: attendee })).json().zones.find((row: { id: string }) => row.id === id).name, zone.name);
    const point = { name: 'Puesto de prueba', kind: 'MEDICAL', zoneId: id, reference: 'Sector norte', active: true };
    const createPoint = await app.inject({ method: 'POST', url: root + '/operational-points', headers: organizer,
      payload: { ...point, version: 3 } });
    assert.equal(createPoint.statusCode, 201, createPoint.body);
    const pointId = createPoint.json().point.id;
    const editPoint = await app.inject({ method: 'PATCH', url: root + '/operational-points/' + pointId, headers: organizer,
      payload: { ...point, reference: 'Sector este', version: 4 } });
    assert.equal(editPoint.statusCode, 200, editPoint.body);
    assert.equal(editPoint.json().point.id, pointId);
    const staffView = (await app.inject({ url: root + '/venue', headers: staff })).json();
    assert.equal(staffView.points[0].reference, 'Sector este');
    assert.equal(staffView.version, undefined);

    const incident = { mode: 'SOS', zoneId: id, clientRequestId: randomUUID() };
    const created = await app.inject({ method: 'POST', url: root + '/incidents', headers: attendee, payload: incident });
    assert.equal(created.statusCode, 201, created.body);
    const zoneUrl = root + '/zones/' + id;
    const editZone = (version: number) => app.inject({ method: 'PATCH', url: zoneUrl, headers: organizer,
      payload: { name: 'Escenario este', description: zone.description, active: false, version } });
    const stale = await editZone(2);
    assert.equal(stale.statusCode, 409);
    assert.equal(stale.json().error.details.currentVersion, 5);
    const updated = await editZone(5);
    assert.equal(updated.statusCode, 200, updated.body);
    assert.equal(updated.json().zone.id, id);
    assert.equal(updated.json().zone.kind, zone.kind);
    assert.equal(updated.json().zone.reference, zone.reference);

    assert.ok(!(await app.inject({ url: root + '/zones', headers: attendee })).json().zones.some((row: { id: string }) => row.id === id));
    const nowStaff = (await app.inject({ url: root + '/venue', headers: staff })).json();
    assert.deepEqual(nowStaff.points, []);
    assert.ok(!nowStaff.zones.some((row: { id: string }) => row.id === id));
    const retry = await app.inject({ method: 'POST', url: root + '/incidents', headers: attendee, payload: incident });
    assert.equal(retry.statusCode, 200);
    assert.equal(retry.json().incident.id, created.json().incident.id);
    assert.equal(retry.json().incident.zoneId, id);
    const invalid = await app.inject({ method: 'POST', url: root + '/incidents', headers: attendee,
      payload: { ...incident, clientRequestId: randomUUID() } });
    assert.equal(invalid.statusCode, 400);
    await app.close();
    db.close();

    db = openDb(path);
    migrate(db);
    seed(db);
    app = createApp(db, pins);
    const persisted = (await app.inject({ url: root + '/venue', headers: organizer })).json();
    assert.deepEqual(persisted.venue, venue);
    assert.equal(persisted.version, 6);
    assert.equal(persisted.zones.find((row: { id: string }) => row.id === id).active, false);
    assert.equal(persisted.points[0].zoneName, 'Escenario este');
    assert.equal(persisted.points[0].id, pointId);
    const audit = db.prepare('SELECT entity, action, actor_person_id, changed_at FROM venue_configuration_changes ORDER BY revision').all();
    assert.deepEqual(audit.map(({ entity, action }) => [entity, action]),
      [['VENUE', 'UPDATE'], ['ZONE', 'CREATE'], ['POINT', 'CREATE'], ['POINT', 'UPDATE'], ['ZONE', 'DEACTIVATE']]);
    assert.ok(audit.every(row => row.actor_person_id === 'demo-organizador' && Number.isFinite(Date.parse(String(row.changed_at)))));
    assert.deepEqual(db.prepare('PRAGMA foreign_key_check').all(), []);

    const legacyUpdate = await app.inject({ method: 'PATCH', url: root, headers: organizer, payload: { name: 'Otro nombre', venue: venue.venue } });
    assert.equal(legacyUpdate.statusCode, 200, legacyUpdate.body);
    assert.equal(legacyUpdate.json().version, undefined);
    const afterLegacy = (await app.inject({ url: root + '/venue', headers: organizer })).json();
    assert.equal(afterLegacy.version, 7);
    assert.equal(afterLegacy.venue.description, venue.description);
  } finally {
    await app.close();
    if (db.isOpen) db.close();
    rmSync(dir, { recursive: true, force: true });
  }
});

test('private venue reads and all mutations are scoped to role and event', async t => {
  const db = openDb(':memory:');
  migrate(db);
  seed(db);
  const app = createApp(db, pins);
  t.after(async () => { await app.close(); db.close(); });
  db.exec(`INSERT INTO events (id, name, venue, starts_at, ends_at, status)
    SELECT 'otro', 'Otro festival', venue, starts_at, ends_at, status FROM events WHERE id = 'demo-festival';
    INSERT INTO zones (event_id, id, name) VALUES ('otro', 'privada', 'Zona privada');`);
  const organizer = await login(app, 'organizador', pins.ORGANIZER);
  const staff = await login(app, 'guardia', pins.STAFF);
  const attendee = await login(app, 'antonia');
  const other = '/api/v1/events/otro';
  assert.equal((await app.inject({ url: root + '/venue' })).statusCode, 401);
  assert.equal((await app.inject({ url: root + '/venue', headers: attendee })).statusCode, 403);
  for (const headers of [staff, organizer]) {
    assert.equal((await app.inject({ url: other + '/venue', headers })).statusCode, 403);
  }
  const point = { name: 'Puerta', zoneId: 'privada', kind: 'EXIT', active: true, reference: 'Norte', version: 1 };
  for (const headers of [staff, attendee]) {
    assert.equal((await app.inject({ method: 'PATCH', url: root, headers, payload: { ...venue, version: 1 } })).statusCode, 403);
    assert.equal((await app.inject({ method: 'POST', url: root + '/zones', headers, payload: { ...zone, version: 1 } })).statusCode, 403);
    assert.equal((await app.inject({ method: 'POST', url: root + '/operational-points', headers, payload: point })).statusCode, 403);
  }
  assert.equal((await app.inject({ method: 'PATCH', url: other + '/zones/privada', headers: organizer,
    payload: { ...zone, version: 1 } })).statusCode, 403);
  assert.equal((await app.inject({ method: 'PATCH', url: root + '/zones/privada', headers: organizer,
    payload: { ...zone, version: 1 } })).statusCode, 404);
  assert.equal((await app.inject({ method: 'POST', url: root + '/operational-points', headers: organizer, payload: point })).statusCode, 400);
  assert.equal((await app.inject({ method: 'POST', url: root + '/zones', headers: organizer,
    payload: { ...zone, kind: 'uncontrolled', version: 1 } })).statusCode, 400);
  assert.equal(db.prepare('SELECT count(*) AS n FROM venue_configuration_changes').get()?.n, 0);
});

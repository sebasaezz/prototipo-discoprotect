import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { createApp } from '../src/app.js';
import { migrate, openDb } from '../src/db.js';
import { boundarySchema, withinBoundary } from '../src/event-map.js';
import { seed } from '../src/seed.js';

const boundary = boundarySchema.parse(JSON.parse(readFileSync(
  new URL('../../fixtures/parque-ohiggins-osm-14817188.geojson', import.meta.url), 'utf8',
)));
const inside = [-70.66095, -33.46310] as [number, number];
const inBoundsButOutside = [-70.6570, -33.46995] as [number, number];
const root = '/api/v1';
const event = '/events/demo-festival';

test('WGS84 park polygon handles the edge, invalid coordinates and points outside the polygon within camera bounds', () => {
  assert.ok(withinBoundary(boundary, inside));
  assert.ok(withinBoundary(boundary, boundary.coordinates[0][0]));
  assert.ok(!withinBoundary(boundary, inBoundsButOutside));
  assert.ok(!withinBoundary(boundary, [NaN, inside[1]]));
  assert.ok(!withinBoundary(boundary, [180.1, inside[1]]));
  assert.ok(!boundarySchema.safeParse({ type: 'Polygon', coordinates: [[[0, 0], [1, 0], [1, 1], [0, 1]]] }).success);
  const withHole = boundarySchema.parse({ type: 'Polygon', coordinates: [
    [[0, 0], [4, 0], [4, 4], [0, 4], [0, 0]], [[1, 1], [1, 3], [3, 3], [3, 1], [1, 1]],
  ] });
  assert.ok(!withinBoundary(withHole, [2, 2]));
  assert.ok(withinBoundary(withHole, [1, 2]));
});

test('authorized map follows the live venue catalog and exposes only marked public points', async t => {
  const db = openDb(':memory:');
  migrate(db);
  seed(db);
  installMap(db);
  db.exec(`INSERT INTO operational_points (event_id, id, zone_id, name, kind, reference, active)
    VALUES ('demo-festival', 'public', 'acceso', 'Puesto público', 'MEDICAL', 'Toldo', 1),
    ('demo-festival', 'private', 'acceso', 'Personal interno', 'SECURITY', 'Referencia reservada', 1)`);
  db.prepare('INSERT INTO public_point_map_positions VALUES (?, ?, ?, ?, 1)').run('demo-festival', 'public', inside[1], inside[0]);
  db.prepare('INSERT INTO zone_map_positions VALUES (?, ?, ?, ?, 1)').run('demo-festival', 'acceso', inside[1], inside[0]);
  const app = createApp(db, { ORGANIZER: 'testing-organizer' });
  t.after(async () => { await app.close(); db.close(); });
  const read = (headers?: { authorization: string }, endpoint = event) =>
    app.inject({ url: root + endpoint + '/map', headers });
  assert.equal((await read()).statusCode, 401);
  const login = await app.inject({ method: 'POST', url: root + '/demo/sessions', payload: { account: 'antonia' } });
  const headers = { authorization: 'Bearer ' + login.json().token };
  const organizer = await app.inject({ method: 'POST', url: root + '/demo/sessions',
    payload: { account: 'organizador', pin: 'testing-organizer' } });
  const admin = { authorization: 'Bearer ' + organizer.json().token };
  const initial = await read(headers);
  assert.equal(initial.statusCode, 200);
  assert.deepEqual(initial.json().map.boundary, boundary);
  assert.deepEqual(initial.json().map.points.map((point: { id: string }) => point.id), ['public']);
  assert.equal(initial.json().map.zones.find((zone: { id: string }) => zone.id === 'acceso').position.simulated, true);
  assert.equal(initial.json().map.zones.find((zone: { id: string }) => zone.id === 'general').position, null);
  const edit = await app.inject({ method: 'PATCH', url: root + event + '/zones/acceso', headers: admin,
    payload: { version: 1, name: "Entrada Perry's Stage", description: 'Cartel de prueba.', active: true } });
  assert.equal(edit.statusCode, 200);
  const updated = (await read(headers)).json().map;
  assert.equal(updated.revision, 2);
  assert.equal(updated.zones.find((zone: { id: string }) => zone.id === 'acceso').name, "Entrada Perry's Stage");

  const otherId = randomUUID();
  db.prepare(`INSERT INTO events (id, name, venue, starts_at, ends_at, status)
    SELECT ?, name, venue, starts_at, ends_at, status FROM events WHERE id = 'demo-festival'`).run(otherId);
  assert.equal((await read(headers, '/events/' + otherId)).statusCode, 403);
});

test('geographic SOS is atomic, owner and event scoped, idempotent and durable across restart', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'dp-geo-'));
  const path = join(dir, 'test.sqlite');
  let db = openDb(path);
  migrate(db);
  seed(db);
  installMap(db);
  let app = createApp(db, { STAFF: 'testing-guard' });
  t.after(async () => { await app.close(); db.close(); rmSync(dir, { recursive: true }); });
  const login = async (account: string, pin?: string) => {
    const result = await app.inject({ method: 'POST', url: root + '/demo/sessions', payload: { account, pin } });
    assert.equal(result.statusCode, 201);
    return { authorization: 'Bearer ' + result.json().token };
  };
  const headers = await login('antonia');
  const another = await login('martin');
  const staff = await login('guardia', 'testing-guard');
  const coordinates = { latitude: inside[1], longitude: inside[0], accuracyMeters: null,
    capturedAt: '2026-11-20T18:00:00.000Z', source: 'DEMO' };
  const payload = { clientRequestId: randomUUID(), mode: 'SOS', geoLocation: coordinates };
  const create = (body: object) => app.inject({ method: 'POST', url: root + event + '/incidents', headers, payload: body });
  const created = await create(payload);
  assert.equal(created.statusCode, 201, created.body);
  const id = created.json().incident.id as string;
  assert.deepEqual(created.json().incident.geoLocation, coordinates);
  assert.equal(created.json().conversation.control, 'AUTO');
  const duplicate = await create(payload);
  assert.equal(duplicate.statusCode, 200);
  assert.equal(duplicate.json().incident.id, id);
  const requestConflict = await create({ ...payload, geoLocation: { ...coordinates, source: 'MANUAL' } });
  assert.equal(requestConflict.statusCode, 409);
  assert.equal(db.prepare('SELECT count(*) AS n FROM incident_geo_locations').get()?.n, 1);
  assert.equal(db.prepare('SELECT event_id AS eventId FROM incident_geo_locations WHERE incident_id = ?').get(id)?.eventId,
    'demo-festival');
  const outside = { ...coordinates, latitude: inBoundsButOutside[1], longitude: inBoundsButOutside[0] };
  for (const geoLocation of [{ ...outside, source: 'MANUAL' }, { ...outside, source: 'DEMO' },
    { ...outside, latitude: 91 }, { ...coordinates, accuracyMeters: -1 }, { ...coordinates, source: 'FAKE' }]) {
    assert.equal((await create({ ...payload, clientRequestId: randomUUID(), geoLocation })).statusCode, 400);
  }
  db.exec('UPDATE event_maps SET demo_enabled = 0');
  assert.equal((await create(payload)).statusCode, 200);
  assert.equal((await create({ ...payload, clientRequestId: randomUUID() })).statusCode, 400);
  const gps = { ...outside, source: 'GPS', accuracyMeters: 52 };
  const outsideGps = await create({ ...payload, clientRequestId: randomUUID(), geoLocation: gps });
  assert.equal(outsideGps.statusCode, 201);
  assert.deepEqual(outsideGps.json().incident.geoLocation, gps);
  const legacy = await create({ mode: 'SOS', clientRequestId: randomUUID() });
  assert.equal(legacy.statusCode, 201);
  assert.equal(legacy.json().incident.geoLocation, undefined);

  await app.close();
  db.close();
  db = openDb(path);
  migrate(db);
  app = createApp(db, {});
  const own = root + '/me/incidents/' + id;
  assert.deepEqual((await app.inject({ url: own, headers })).json().geoLocation, coordinates);
  assert.equal((await app.inject({ url: own, headers: another })).statusCode, 404);
  const dispatch = await app.inject({ url: root + event + '/incidents/' + id + '/dispatch', headers: staff });
  assert.equal(dispatch.statusCode, 200);
  assert.deepEqual(dispatch.json().dispatch.geoLocation, coordinates);
  assert.equal(dispatch.json().dispatch.confirmedLocation, null);
  assert.deepEqual(db.prepare('PRAGMA foreign_key_check').all(), []);
});

function installMap(db: ReturnType<typeof openDb>) {
  db.prepare('INSERT INTO event_maps (event_id, boundary_json, source_url, city, demo_enabled) VALUES (?, ?, ?, ?, 1)').run('demo-festival', JSON.stringify(boundary),
    'https://www.openstreetmap.org/way/14817188', 'Santiago de Chile');
}

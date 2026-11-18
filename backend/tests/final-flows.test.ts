import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test, type TestContext } from 'node:test';
import { createApp } from '../src/app.js';
import { openDb, migrate } from '../src/db.js';
import { seed } from '../src/seed.js';

const api = '/api/v1';
const event = api + '/events/demo-festival';
const pins = { ORGANIZER: 'test-organizer', STAFF: 'test-staff', STAFF_2: 'test-second-staff' };

async function fixture(t: TestContext) {
  const db = openDb(':memory:');
  migrate(db);
  seed(db);
  const app = createApp(db, pins);
  t.after(async () => { await app.close(); db.close(); });
  async function login(account: string, pin?: string) {
    const reply = await app.inject({ method: 'POST', url: api + '/demo/sessions', payload: { account, pin } });
    assert.equal(reply.statusCode, 201);
    return { authorization: 'Bearer ' + reply.json().token };
  }
  async function send(headers: Awaited<ReturnType<typeof login>>, url: string, status: number,
    payload?: object, method: 'GET' | 'POST' | 'PATCH' = payload ? 'POST' : 'GET') {
    const response = await app.inject({ url, method, headers, payload });
    assert.equal(response.statusCode, status, `${method} ${url}: ${response.body}`);
    return response.json();
  }
  const organizer = await login('organizador', pins.ORGANIZER);
  const staff = await login('guardia', pins.STAFF);
  const second = await login('guardia2', pins.STAFF_2);
  const attendee = await login('antonia');
  const companion = await login('martin');
  return { db, app, send, login, organizer, staff, second, attendee, companion };
}

test('event configuration, venue, replacement and statistics preserve real lifecycle data', async t => {
  const { db, send, organizer, staff, second, attendee, login } = await fixture(t);
  const id = randomUUID();
  const draft = { id, name: 'Festival ficticio B', venue: 'Recinto B', startsAt: '2026-11-14T20:00:00-03:00',
    endsAt: '2026-11-15T04:00:00-03:00', status: 'PLANNED', demoAttendees: true };
  const path = api + '/events/' + id;
  await send(attendee, api + '/events', 403, draft);
  await send(organizer, api + '/events', 200, draft);
  await send(organizer, api + '/events', 200, draft);
  assert.equal((await send(attendee, api + '/events', 200)).events.find((item: { id: string }) => item.id === id).role, 'ATTENDEE');
  const schedule = await send(organizer, path + '/schedule', 200);
  const update = { ...schedule, status: 'ACTIVE' };
  await send(organizer, path + '/schedule', 200, update, 'PATCH');
  await send(organizer, path + '/schedule', 409, update, 'PATCH');
  const zone = (await send(organizer, path + '/zones', 201,
    { name: 'Entrada', description: 'Junto al toro mecánico', active: true, version: 2 })).zone.id;
  await send(organizer, path + '/operational-points', 201,
    { name: 'Puesto', kind: 'MEDICAL', zoneId: zone, reference: 'Letrero amarillo', active: true, version: 3 });
  await send(staff, path + '/venue', 403);
  await send(staff, path + '/staff', 403);
  for (const personId of ['demo-guardia', 'demo-guardia2']) {
    await send(organizer, path + '/staff', 200, { personId, active: true, operationId: randomUUID() }, 'PATCH');
  }
  await send(organizer, path + '/staff', 404, { personId: 'demo-antonia', active: true, operationId: randomUUID() }, 'PATCH');
  await send(organizer, path + '/staff', 403, { personId: 'demo-guardia2', active: false,
    replacementId: randomUUID(), operationId: randomUUID() }, 'PATCH');
  const queue = path + '/incidents';
  const first = (await send(attendee, queue, 201, { mode: 'SOS', zoneId: zone, clientRequestId: randomUUID() })).incident.id;
  const secondId = (await send(attendee, queue, 201, { mode: 'SOS', zoneId: zone, clientRequestId: randomUUID() })).incident.id;
  const operate = (headers: typeof staff, action: string, version: number) =>
    send(headers, queue + '/' + first + '/' + action, 200, { version, operationId: randomUUID() });
  await operate(staff, 'claim', 1);
  await operate(staff, 'start', 2);
  await send(organizer, path + '/staff', 409, { personId: 'demo-guardia', active: false, operationId: randomUUID() }, 'PATCH');
  const change = { personId: 'demo-guardia', active: false, replacementId: 'demo-guardia2', operationId: randomUUID() };
  const outcomes = await Promise.all([send(organizer, path + '/staff', 200, change, 'PATCH'),
    send(organizer, path + '/staff', 200, change, 'PATCH')]);
  assert.deepEqual(outcomes.map(result => result.replayed).sort(), [false, true]);
  await send(staff, queue, 401);
  await send(await login('guardia', pins.STAFF), queue, 403);
  await operate(second, 'resolve', 4);
  assert.deepEqual(db.prepare('SELECT from_status, to_status FROM incident_transitions WHERE incident_id = ? ORDER BY applied_version')
    .all(first).map(row => [row.from_status, row.to_status]), [['OPEN','ASSIGNED'], ['ASSIGNED','IN_PROGRESS'], ['IN_PROGRESS','RESOLVED']]);
  assert.equal(db.prepare('SELECT count(*) AS n FROM event_staff_audit WHERE reassigned_incident_id = ?').get(first)?.n, 1);
  db.prepare(`UPDATE incidents SET created_at = ?, assigned_at = ?, started_at = ?, resolved_at = ?, updated_at = ? WHERE id = ?`)
    .run('2026-11-15T02:50:00.000Z', '2026-11-15T02:51:00.000Z', '2026-11-15T02:52:00.000Z',
      '2026-11-15T03:00:00.000Z', '2026-11-15T03:00:00.000Z', first);
  db.prepare('UPDATE incidents SET created_at = ? WHERE id = ?').run('2026-11-15T03:01:00.000Z', secondId);
  const stats = path + '/statistics';
  await send(attendee, stats, 403);
  await send(organizer, stats + '?from=2026-11-15&through=2026-11-14', 400);
  const all = await send(organizer, stats, 200);
  assert.equal(all.total, db.prepare('SELECT count(*) AS total FROM incidents WHERE event_id = ?').get(id)?.total);
  assert.deepEqual(all.byStatus, { RESOLVED: 1, OPEN: 1 });
  assert.deepEqual(all.byZone, { [zone]: 2 });
  assert.deepEqual(all.daily, { '2026-11-14': 1, '2026-11-15': 1 });
  const one = await send(organizer, stats + `?from=2026-11-14&through=2026-11-14&status=RESOLVED&zoneId=${zone}`, 200);
  const independent = db.prepare(`SELECT unixepoch(assigned_at)-unixepoch(created_at) AS assigned,
    unixepoch(started_at)-unixepoch(created_at) AS started, unixepoch(resolved_at)-unixepoch(created_at) AS closed
    FROM incidents WHERE id = ?`).get(first);
  assert.equal(one.total, 1);
  for (const field of ['assigned', 'started', 'closed']) {
    assert.deepEqual(one.durations[field], { count: 1, meanSeconds: independent?.[field],
      medianSeconds: independent?.[field], p90Seconds: independent?.[field] });
  }
  assert.equal((await send(organizer, stats + '?status=OPEN', 200)).durations.assigned, null);
  assert.doesNotMatch(JSON.stringify(one), /Antonia|description|requester/);
  assert.deepEqual(db.prepare('PRAGMA foreign_key_check').all(), []);
});

test('tickets are opaque, single-use, event scoped, and safe to replay', async t => {
  const { db, send, organizer, staff, second, attendee, companion } = await fixture(t);
  const expiresAt = new Date(Date.now() + 3600_000).toISOString();
  const tickets = event + '/tickets';
  const input = { attendeeId: 'demo-antonia', expiresAt };
  const { id } = await send(organizer, tickets, 200, input);
  assert.equal((await send(organizer, tickets, 200, input)).id, id);
  assert.equal((await send(companion, tickets + '/mine', 200)).ticket, null);
  const { code } = (await send(attendee, tickets + '/mine', 200)).ticket;
  assert.match(code, /^DP1_[A-Za-z0-9_-]{32}$/);
  assert.ok(!JSON.stringify(await send(organizer, tickets, 200)).includes(code));
  assert.ok(!JSON.stringify(await send(organizer, event + '/search?type=tickets&id=' + id, 200)).includes(code));
  await send(staff, event + '/search?type=tickets&id=' + id, 403);
  await send(attendee, tickets + '/validate', 403, { code, operationId: randomUUID() });
  await send(staff, api + '/nfc/resolve', 400, { token: code, eventId: 'demo-festival' });
  const check = { code, operationId: randomUUID() };
  const checks = await Promise.all([send(staff, tickets + '/validate', 200, check),
    send(second, tickets + '/validate', 200, { ...check, operationId: randomUUID() })]);
  assert.deepEqual(checks.map(result => result.result).sort(), ['USED', 'VALID']);
  assert.equal((await send(staff, tickets + '/validate', 200, check)).replayed, true);
  await send(staff, tickets + '/validate', 409, { ...check, code: 'alterado' });
  assert.equal((await send(staff, tickets + '/validate', 200, { code: 'alterado', operationId: randomUUID() })).result, 'INVALID');
  const other = randomUUID();
  await send(organizer, api + '/events', 200, { id: other, name: 'Otro', venue: 'Recinto', startsAt: new Date().toISOString(),
    endsAt: expiresAt, status: 'ACTIVE', demoAttendees: false });
  const otherTickets = api + '/events/' + other + '/tickets';
  assert.equal((await send(organizer, otherTickets + '/validate', 200, { code, operationId: randomUUID() })).result, 'OTHER_EVENT');
  await send(organizer, otherTickets + '/' + id + '/revoke', 404, {});
  await send(organizer, tickets + '/' + id + '/revoke', 200, {});
  assert.equal((await send(staff, tickets + '/validate', 200, { code, operationId: randomUUID() })).result, 'REVOKED');
  await send(organizer, tickets, 200, { ...input, attendeeId: 'demo-martin' });
  const expired = (await send(companion, tickets + '/mine', 200)).ticket.code;
  db.prepare('UPDATE event_tickets SET expires_at = ? WHERE code = ?').run('2020-01-01T00:00:00.000Z', expired);
  assert.equal((await send(staff, tickets + '/validate', 200, { code: expired, operationId: randomUUID() })).result, 'EXPIRED');
});

test('companion consent limits readers and promotional visibility is entirely server controlled', async t => {
  const { db, send, organizer, staff, second, attendee, companion } = await fixture(t);
  const path = event + '/companions';
  const invite = await send(attendee, path + '/invite', 200, {});
  await send(attendee, path + '/accept', 404, { code: invite.code });
  const { id } = await send(companion, path + '/accept', 200, { code: invite.code });
  assert.equal((await send(companion, path + '/accept', 200, { code: invite.code })).id, id);
  assert.deepEqual((await send(attendee, path, 200)).companions, [{ id, name: 'Martín Ejemplo' }]);
  await send(staff, path, 403);
  await send(organizer, path, 403);
  const incident = (await send(attendee, event + '/incidents', 201, { mode: 'SOS', clientRequestId: randomUUID() })).incident.id;
  const detail = event + '/incidents/' + incident;
  await send(staff, detail + '/companions', 404);
  await send(staff, detail + '/claim', 200, { version: 1, operationId: randomUUID() });
  await send(second, detail + '/companions', 404);
  assert.deepEqual((await send(staff, detail + '/companions', 200)).companions, [{ id, name: 'Martín Ejemplo' }]);
  const found = await send(staff, event + '/search?type=incidents&status=ASSIGNED&id=' + incident, 200);
  assert.deepEqual(Object.keys(found.results[0]).sort(), ['createdAt', 'id', 'status']);
  await send(attendee, event + '/search?type=incidents', 403);
  await send(companion, path + '/' + id + '/unlink', 200, {});
  assert.deepEqual((await send(staff, detail + '/companions', 200)).companions, []);
  assert.equal(db.prepare('SELECT count(*) AS n FROM companion_access_audit').get()?.n, 2);
  const promos = event + '/promotions';
  const now = Date.now();
  const iso = (delta: number) => new Date(now + delta * 60_000).toISOString();
  const campaign = { title: 'Agua de la demo', description: 'Promoción ficticia', startsAt: iso(-5), endsAt: iso(10),
    imageUrl: null, linkUrl: 'https://example.com/actividad', active: true };
  const record = await send(organizer, promos, 200, campaign);
  await send(organizer, promos, 409, { ...campaign, title: 'AGUA DE LA DEMO' });
  await send(organizer, promos, 400, { ...campaign, title: 'Insegura', linkUrl: 'javascript:alert(1)' });
  await send(organizer, promos, 200, { ...campaign, title: 'Futura', startsAt: iso(4) });
  await send(organizer, promos, 200, { ...campaign, title: 'Pasada', endsAt: iso(-1) });
  await send(organizer, promos, 200, { ...campaign, title: 'Deshabilitada', active: false });
  assert.deepEqual((await send(attendee, promos, 200)).promotions.map((row: { id: string }) => row.id), [record.id]);
  await send(organizer, promos + '/' + record.id, 200, { ...campaign, active: false, version: 1 }, 'PATCH');
  await send(organizer, promos + '/' + record.id, 409, { ...campaign, version: 1 }, 'PATCH');
  assert.deepEqual((await send(attendee, promos, 200)).promotions, []);
});

test('real HTTP restart preserves the SOS receipt and idempotency', async t => {
  const folder = mkdtempSync(join(tmpdir(), 'discoprotect-final-'));
  const path = join(folder, 'integration.sqlite');
  let db = openDb(path);
  migrate(db);
  seed(db);
  let app = createApp(db, pins);
  t.after(async () => { await app.close(); db.close(); rmSync(folder, { recursive: true }); });
  let origin = await app.listen({ host: '127.0.0.1', port: 0 });
  const session = await fetch(origin + api + '/demo/sessions', { method: 'POST',
    headers: { 'content-type': 'application/json' }, body: JSON.stringify({ account: 'antonia' }) });
  assert.equal(session.status, 201);
  const headers = { authorization: 'Bearer ' + (await session.json() as { token: string }).token, 'content-type': 'application/json' };
  const body = JSON.stringify({ clientRequestId: randomUUID(), mode: 'SOS' });
  const url = event + '/incidents';
  const first = await fetch(origin + url, { method: 'POST', headers, body });
  assert.equal(first.status, 201);
  const receipt = await first.json() as { incident: { id: string } };
  await app.close();
  db.close();
  db = openDb(path);
  migrate(db);
  app = createApp(db, pins);
  origin = await app.listen({ host: '127.0.0.1', port: 0 });
  const replay = await fetch(origin + url, { method: 'POST', headers, body });
  assert.equal(replay.status, 200);
  assert.equal((await replay.json() as typeof receipt).incident.id, receipt.incident.id);
  assert.deepEqual(db.prepare('SELECT count(*) AS n FROM incidents').get()?.n, 1);
});

test('the final demo seed serves historical statistics and refuses to overwrite a database', async t => {
  const folder = mkdtempSync(join(tmpdir(), 'discoprotect-final-seed-'));
  const path = join(folder, 'seed.sqlite');
  const seedScript = fileURLToPath(new URL('../../scripts/seed-final.mjs', import.meta.url));
  const runSeed = () => execFileSync(process.execPath, [seedScript, path], { stdio: 'pipe' });
  runSeed();
  assert.throws(runSeed, /EEXIST/);
  const db = openDb(path);
  const app = createApp(db, pins);
  t.after(async () => { await app.close(); db.close(); rmSync(folder, { recursive: true }); });
  const organizer = await app.inject({ method: 'POST', url: api + '/demo/sessions',
    payload: { account: 'organizador', pin: pins.ORGANIZER } });
  const headers = { authorization: 'Bearer ' + organizer.json().token };
  const stats = await app.inject({ url: event + '/statistics', headers });
  assert.equal(stats.statusCode, 200, stats.body);
  assert.equal(stats.json().total, 3);
  assert.equal(stats.json().resolved, 2);
  const queue = await app.inject({ url: event + '/incidents?order=dispatch', headers });
  assert.equal(queue.statusCode, 200, queue.body);
  assert.equal(queue.json().incidents.length, 3);
  assert.deepEqual(db.prepare('PRAGMA foreign_key_check').all(), []);
});

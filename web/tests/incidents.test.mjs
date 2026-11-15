import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { ApiError, events, incidents, login } from '../.test-build/api.js';
import { IncidentTable } from '../.test-build/IncidentTable.js';

const incident = {
  id: '34522603-54fe-4ea1-af0b-39699c9515d2',
  eventId: 'demo-festival',
  requesterName: 'Antonia Reyes',
  zoneName: null,
  locationText: '<referencia ficticia>',
  status: 'OPEN',
  createdAt: '2026-11-15T22:00:00.000Z',
};

test('table renders the ID, location, reference, timestamp and textual status', () => {
  const html = renderToStaticMarkup(createElement(IncidentTable, { items: [
    incident, { ...incident, id: 'different', status: 'CANCELLED', zoneName: 'Escenario', locationText: null },
  ] }));
  for (const text of [incident.id, 'Antonia Reyes', 'Ubicación desconocida', '&lt;referencia ficticia&gt;',
    incident.createdAt, 'Abierta', 'Escenario', 'Cancelada']) assert.ok(html.includes(text));
  assert.ok(renderToStaticMarkup(createElement(IncidentTable, { items: [] })).includes('aún no tiene solicitudes'));
});

test('API uses organizer PIN, accepts only its events and sends the Bearer to the same origin', async t => {
  const signal = new AbortController().signal;
  const session = { token: 'a'.repeat(43), expiresAt: new Date(Date.now() + 60000).toISOString() };
  const requests = t.mock.method(globalThis, 'fetch', async (path, options) => {
    assert.equal(options.cache, 'no-store');
    assert.equal(options.signal, signal);
    const json = data => Response.json(data);
    switch (path) {
      case '/api/v1/demo/sessions':
        assert.deepEqual(JSON.parse(options.body), { account: 'organizador', pin: 'prueba-pin' });
        assert.equal(options.method, 'POST');
        assert.equal(options.headers.Authorization, undefined);
        return json({ ...session, role: 'ORGANIZER' });
      case '/api/v1/events':
        assert.equal(options.headers.Authorization, 'Bearer ' + session.token);
        return json({ events: [{ id: 'demo-festival', role: 'ORGANIZER' }, { id: 'otro', role: 'STAFF' }] });
      case '/api/v1/events/demo-festival/incidents':
        assert.equal(options.headers.Authorization, 'Bearer ' + session.token);
        return json({ incidents: [incident] });
      default:
        return Response.json({}, { status: 403 });
    }
  });
  const state = await login('prueba-pin', signal);
  assert.deepEqual(state, session);
  assert.deepEqual((await events(state, signal)).map(event => event.id), ['demo-festival']);
  assert.deepEqual(await incidents(state, 'demo-festival', signal), [incident]);
  await assert.rejects(incidents(state, 'otro', signal), { status: 403 });
  assert.equal(requests.mock.callCount(), 4);
});

test('API rejects wrong roles, expired sessions and incident payloads from another event', async t => {
  const signal = new AbortController().signal;
  const session = { token: 'a'.repeat(43), expiresAt: new Date(Date.now() + 60000).toISOString() };
  const replies = [
    Response.json({ ...session, role: 'STAFF' }),
    Response.json({ ...session, expiresAt: '2000-01-01', role: 'ORGANIZER' }),
    Response.json({}, { status: 401 }),
    Response.json({ incidents: [{ ...incident, eventId: 'otro' }] }),
  ];
  t.mock.method(globalThis, 'fetch', async () => replies.shift());
  await assert.rejects(login('prueba-pin', signal), { status: 403 });
  await assert.rejects(login('prueba-pin', signal), { status: 403 });
  await assert.rejects(events(session, signal), error => error instanceof ApiError && error.status === 401);
  await assert.rejects(incidents(session, 'demo-festival', signal), { status: 502 });
});

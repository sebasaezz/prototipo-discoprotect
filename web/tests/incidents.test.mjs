import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { ApiError, currentIncidentSnapshot, events, incidents, incidentDispatch, incidentTriage, login } from '../.test-build/api.js';
import { IncidentTable } from '../.test-build/IncidentTable.js';
import { incidentPolling } from '../.test-build/incidentPolling.js';

const incident = {
  id: '34522603-54fe-4ea1-af0b-39699c9515d2',
  eventId: 'demo-festival',
  requesterName: 'Antonia Reyes',
  zoneName: null,
  locationText: '<referencia ficticia>',
  status: 'OPEN',
  createdAt: '2026-11-15T22:00:00.000Z',
  updatedAt: '2026-11-15T22:00:00.000Z',
  version: 1,
  assignedStaff: null,
  assignedAt: null,
  startedAt: null,
  resolvedAt: null,
  resolutionNote: null,
};

const suggested = {
  triageVersion: 2, status: 'SUGGESTED', fallback: null, fallbackReason: null, latestReview: null,
  suggestion: { category: 'SECURITY', urgency: 'IMMEDIATE', summary: 'Se reportó un peligro.',
    missingInformation: ['ONGOING_DANGER'], uncertainty: 'HIGH' },
};
const review = { reviewVersion: 3, reviewedTriageVersion: 2, operationId: incident.id, decision: 'CORRECT',
  category: 'OTHER', urgency: 'PRIORITY', reviewedAt: incident.createdAt, actor: { id: 'guard', name: 'Guardia ficticio' } };
const reviewed = { ...suggested, triageVersion: 3, latestReview: review };

test('review and deterministic danger remain visible when operational and triage versions arrive out of order', () => {
  const alert = { reasonCode: 'IMMEDIATE_DANGER', dangerAlert: 'IMMEDIATE_DANGER' };
  const current = { ...incident, ...alert, version: 2, triage: suggested };
  const late = { ...incident, ...alert, triage: reviewed };
  const [merged] = currentIncidentSnapshot([current], [late]);
  assert.equal(merged.version, 2);
  assert.equal(merged.triage, reviewed);
  assert.equal(currentIncidentSnapshot([merged], [current])[0].triage, reviewed);
  const html = renderToStaticMarkup(createElement(IncidentTable, { items: [merged] }));
  for (const text of ['Se reportó peligro inmediato', 'Revisión humana', 'Prioritaria', 'Detalle e historial']) {
    assert.ok(html.includes(text));
  }
});

test('organizer history validates ordering and rejects a leaked original description', async t => {
  const signal = new AbortController().signal;
  const session = { token: 'a'.repeat(43), expiresAt: new Date(Date.now() + 60000).toISOString() };
  const replies = [
    { triage: reviewed, reviews: [review] },
    { triage: reviewed, reviews: [review, review] },
    { triage: reviewed, reviews: [review], description: 'Solo para el guardia' },
  ];
  t.mock.method(globalThis, 'fetch', async (path, options) => {
    assert.equal(path, `/api/v1/events/demo-festival/incidents/${incident.id}/triage`);
    assert.equal(options.headers.Authorization, 'Bearer ' + session.token);
    assert.equal(options.signal, signal);
    return Response.json(replies.shift());
  });
  assert.deepEqual((await incidentTriage(session, incident.eventId, incident.id, signal)).reviews, [review]);
  await assert.rejects(incidentTriage(session, incident.eventId, incident.id, signal), { status: 502 });
  await assert.rejects(incidentTriage(session, incident.eventId, incident.id, signal), { status: 502 });
});

test('table renders the ID, location, reference, timestamp and textual status', () => {
  const html = renderToStaticMarkup(createElement(IncidentTable, { items: [
    incident, { ...incident, id: 'different', status: 'CANCELLED', zoneName: 'Escenario', locationText: null },
  ] }));
  for (const text of [incident.id, 'Antonia Reyes', 'Ubicación desconocida', '&lt;referencia ficticia&gt;',
    incident.createdAt, 'Abierta', 'Escenario', 'Cancelada']) assert.ok(html.includes(text));
  assert.ok(renderToStaticMarkup(createElement(IncidentTable, { items: [] })).includes('aún no tiene solicitudes'));
});

test('dispatch view distinguishes provisional P0 and pending and honors server queue order', () => {
  const provisional = { effectivePriority: 'P0', prioritySource: 'AI_PROVISIONAL',
    priorityReasons: ['Peligro textual identificado por IA'], elapsedSeconds: 20, revision: 0,
    inferredLocation: { zoneName: 'Escenario' }, analysis: { summary: 'Caída reportada', uncertainty: [] },
  };
  const pending = { ...provisional, effectivePriority: 'PENDING', prioritySource: 'PENDING', inferredLocation: null, analysis: null };
  const items = [
    { ...incident, requesterName: 'Primero en cola', hasConversation: true, dispatch: provisional },
    { ...incident, id: 'other', requesterName: 'Segundo en cola', hasConversation: true, dispatch: pending },
  ];
  const html = renderToStaticMarkup(createElement(IncidentTable, { items }));
  assert.ok(html.indexOf('Primero en cola') < html.indexOf('Segundo en cola'));
  for (const text of ['Provisional IA', 'Pendiente de evaluación', 'Inferida por IA', 'Despacho y auditoría']) {
    assert.ok(html.includes(text));
  }
});

test('dispatch audit rejects a leaked conversation', async t => {
  const session = { token: 'a'.repeat(43), expiresAt: new Date(Date.now() + 60000).toISOString() };
  const result = { dispatch: { effectivePriority: 'PENDING', revision: 0, elapsedSeconds: 0, priorityReasons: [] },
    reviews: [], transitions: [], messages: [] };
  t.mock.method(globalThis, 'fetch', async path => {
    assert.ok(path.endsWith('/dispatch'));
    return Response.json(result);
  });
  await assert.rejects(incidentDispatch(session, incident.eventId, incident.id, new AbortController().signal), { status: 502 });
});

test('catalog location and its provenance take precedence over a raw chat quote', () => {
  const dispatch = {
    effectivePriority: 'P0', prioritySource: 'AI_PROVISIONAL', priorityReasons: [], elapsedSeconds: 10, revision: 0,
    declaredLocation: { zoneId: null, zoneName: null, text: 'en el toro mecánico' },
    inferredLocation: { zoneId: 'acceso', zoneName: 'Acceso principal', text: null },
    confirmedLocation: null, analysis: null,
    attendeeLocation: { zoneId: 'acceso', zoneName: 'Acceso principal', source: 'CONFIRMED_AI',
      messageSeq: 3, evidence: { messageSeq: 1, quote: 'en el toro mecánico', feature: null } },
  };
  const html = renderToStaticMarkup(createElement(IncidentTable, { items: [{ ...incident, dispatch }] }));
  assert.match(html, /Confirmada por el asistente · Acceso principal/);
  assert.match(html, /Pendiente de verificación en terreno/);
  assert.match(html, /Evidencia original · «en el toro mecánico»/);
  const reviewed = { ...dispatch, confirmedLocation: { zoneId: 'escenario', zoneName: 'Escenario', text: null } };
  const reviewedHtml = renderToStaticMarkup(createElement(IncidentTable, { items: [{ ...incident, dispatch: reviewed }] }));
  assert.match(reviewedHtml, /Confirmada por el guardia · Escenario/);
  assert.doesNotMatch(reviewedHtml, /Pendiente de verificación en terreno/);
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
      case '/api/v1/events/demo-festival/incidents?order=dispatch':
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

test('operational view renders the assigned guard, transition times and private resolution note', () => {
  const resolvedAt = '2026-11-15T22:01:00.000Z';
  const resolved = { ...incident, status: 'RESOLVED', version: 4, assignedAt: incident.createdAt,
    startedAt: incident.createdAt, resolvedAt, updatedAt: resolvedAt,
    assignedStaff: { id: 'demo-guardia2', name: 'Guardia Demo 2' }, resolutionNote: 'Atención completada' };
  const html = renderToStaticMarkup(createElement(IncidentTable, { items: [resolved] }));
  for (const text of ['Resuelta', 'Guardia Demo 2', resolvedAt, 'Atención completada']) assert.ok(html.includes(text));
  assert.deepEqual(currentIncidentSnapshot([resolved], [incident]), [resolved]);
});

test('visible polling coalesces refreshes, backs off, pauses and discards late results', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const requests = [];
  const items = [];
  const errors = [];
  const polling = incidentPolling(signal => new Promise((resolve, reject) => requests.push({ signal, resolve, reject })),
    item => items.push(item), error => { errors.push(error); return true; });
  polling.setVisible(true);
  polling.refresh();
  assert.equal(requests.length, 1);
  requests[0].reject('offline');
  await Promise.resolve();
  t.mock.timers.tick(9999);
  assert.equal(requests.length, 1);
  t.mock.timers.tick(1);
  assert.equal(requests.length, 2);
  polling.setVisible(false);
  assert.equal(requests[1].signal.aborted, true);
  t.mock.timers.tick(30000);
  assert.equal(requests.length, 2);
  polling.setVisible(true);
  requests[2].resolve('vigente');
  requests[1].resolve('tardía');
  await Promise.resolve();
  assert.deepEqual(items, ['vigente']);
  assert.deepEqual(errors, ['offline']);
  t.mock.timers.tick(5000);
  assert.equal(requests.length, 4);
  polling.setVisible(false);
});

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { activeIncident, previewDemoPurge, purgeDemo, withdrawIncident } from '../.test-build/api.js';

test('H7.3 organizer sends scoped withdrawal and confirmed purge commands', async t => {
  const session = { token: 'fictional-token' };
  const target = { id: 'a09be708-529f-4e07-9900-87a521a24b54', eventId: 'demo-festival' };
  const operationId = '21435ada-688c-497a-ad0a-1a60f40e886f';
  const hash = '1'.repeat(64);
  const requests = [];
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    requests.push({ url, method: init.method, body: init.body ? JSON.parse(init.body) : null });
    assert.equal(init.headers.Authorization, 'Bearer ' + session.token);
    return Response.json({ enabled: true, count: 1, selectionHash: hash });
  });
  const sample = await previewDemoPurge(session, target.eventId, new AbortController().signal, target.id);
  assert.equal(sample.count, 1);
  const purge = { incidentId: target.id, operationId, selectionHash: sample.selectionHash, confirmation: 'ELIMINAR' };
  const withdraw = { version: 2, operationId, resolutionNote: 'Caso ficticio' };
  await purgeDemo(session, target.eventId, purge);
  await withdrawIncident(session, target, withdraw);
  const base = '/api/v1/events/demo-festival/incidents';
  assert.deepEqual(requests, [
    { url: base + '/demo-purge?incidentId=' + target.id, method: 'GET', body: null },
    { url: base + '/demo-purge', method: 'POST', body: purge },
    { url: base + '/' + target.id + '/withdraw', method: 'POST', body: withdraw },
  ]);
  assert.equal(activeIncident({ status: 'IN_PROGRESS' }), true);
  assert.equal(activeIncident({ status: 'WITHDRAWN' }), false);
  assert.equal(activeIncident({ status: 'CLOSED' }), false);
});

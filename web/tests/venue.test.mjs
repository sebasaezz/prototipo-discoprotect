import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { VenueForm } from '../.test-build/VenueForms.js';
import { readVenue, writeVenue } from '../.test-build/venueApi.js';

const session = { token: 'fictional-test-token' };
const eventId = 'festival de prueba';
const config = {
  eventId, version: 4, venue: { name: 'Festival', venue: 'Recinto sur', description: null, accessNotes: null, responseNotes: null },
  zones: [{ id: 'escenario', name: 'Escenario', active: false }], points: [],
};

test('venue API scopes versioned commands and surfaces conflicts without retrying', async t => {
  const signal = new AbortController().signal;
  const calls = [];
  t.mock.method(globalThis, 'fetch', async (path, options) => {
    assert.equal(options.headers.Authorization, 'Bearer ' + session.token);
    assert.equal(options.cache, 'no-store');
    calls.push([path, options.method, options.body && JSON.parse(options.body)]);
    return calls.length === 1 ? Response.json(config) : calls.length === 2 ? Response.json({}, { status: 409 })
      : Response.json({ ...config, eventId: 'otro' });
  });
  assert.equal((await readVenue(session, eventId, signal)).version, 4);
  const fields = { name: 'Escenario', description: 'Letrero amarillo junto a la valla', active: true };
  await assert.rejects(writeVenue(session, eventId, 4, { entity: 'zone', id: 'escenario', fields }, signal), { status: 409 });
  await assert.rejects(readVenue(session, eventId, signal), { status: 502 });
  assert.deepEqual(calls, [
    ['/api/v1/events/festival%20de%20prueba/venue', 'GET', undefined],
    ['/api/v1/events/festival%20de%20prueba/zones/escenario', 'PATCH', { ...fields, version: 4 }],
    ['/api/v1/events/festival%20de%20prueba/venue', 'GET', undefined],
  ]);
});

test('zone editing uses a place name and a visual description', () => {
  const html = renderToStaticMarkup(createElement(VenueForm, { editor: { entity: 'zone' }, config }));
  assert.match(html, /Lugar/);
  assert.match(html, /Descripción/);
  assert.match(html, /letrero amarillo/);
  assert.doesNotMatch(html, /name="(?:kind|reference)"/);
});

test('point editing exposes controlled types and marks inactive zones', () => {
  const html = renderToStaticMarkup(createElement(VenueForm, { editor: { entity: 'point' }, config }));
  assert.match(html, /Puesto médico/);
  assert.match(html, /Punto de encuentro/);
  assert.match(html, /Escenario · Inactiva/);
  const reference = html.match(/<input(?=[^>]*name="reference")[^>]*>/)?.[0] ?? '';
  assert.match(reference, /maxLength="160"/);
  assert.match(reference, /required/);
});

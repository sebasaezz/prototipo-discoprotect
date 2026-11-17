import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { DashboardOverview } from '../.test-build/DashboardOverview.js';
import { IncidentSync } from '../.test-build/IncidentSync.js';
import { OrganizerLayout } from '../.test-build/OrganizerLayout.js';
import { PendingSection } from '../.test-build/PendingSection.js';
import { incidentPolling } from '../.test-build/incidentPolling.js';
import { historyHref, queueHref, sectionForHash } from '../.test-build/organizerNavigation.js';

test('dashboard counts only active statuses and previews the first five in server order', () => {
  const items = [
    { id: 'terminal-1', status: 'WITHDRAWN' },
    { id: 'first-in-queue', status: 'ASSIGNED', requesterName: 'Detail only', dispatch: {
      effectivePriority: 'P0', confirmedLocation: { zoneName: 'Acceso norte' },
    } },
    { id: 'second-in-queue', status: 'OPEN', locationText: 'Pasillo' },
    { id: 'terminal-2', status: 'CLOSED' },
    { id: 'third-in-queue', status: 'IN_PROGRESS' },
    { id: 'fourth-in-queue', status: 'ASSIGNED' },
    { id: 'fifth-in-queue', status: 'OPEN' },
    { id: 'sixth-in-queue', status: 'OPEN' },
  ];
  const html = renderToStaticMarkup(createElement(DashboardOverview, { items }));
  assert.match(html, /<dt>Activas<\/dt><dd>6/);
  assert.match(html, /<dt>Sin asignar<\/dt><dd>3/);
  assert.match(html, /<dt>Asignadas<\/dt><dd>2/);
  assert.match(html, /<dt>En atención<\/dt><dd>1/);
  assert.deepEqual([...html.matchAll(/<code>(.*?)<\/code>/g)].map(match => match[1]), items
    .filter(item => item.id.endsWith('-in-queue')).slice(0, 5).map(item => item.id));
  assert.match(html, /Acceso norte/);
  assert.match(html, /P0 · Crítica/);
  assert.match(html, /Prioridad no disponible/);
  assert.doesNotMatch(html, /Detail only|terminal-/);
  assert.match(html, /href="#incidentes\/cola"/);
  assert.match(html, /href="#incidentes\/historial"/);
});

test('dashboard distinguishes a verified empty queue from loading and unavailable data', () => {
  const dashboard = renderToStaticMarkup(createElement(DashboardOverview, { items: [] }));
  assert.equal((dashboard.match(/<dd>0<\/dd>/g) ?? []).length, 4);
  assert.match(dashboard, /No hay solicitudes activas/);
  const sync = (lastRead, error = '') => renderToStaticMarkup(createElement(IncidentSync, { lastRead, error }));
  assert.match(sync(null), /Consultando incidentes/);
  assert.doesNotMatch(sync(null, 'La cuenta no está autorizada'), /última consulta disponible|Consultando incidentes/i);
  const lastRead = new Date('2026-11-17T22:00:00Z');
  assert.match(sync(lastRead, 'Caída de red'), /última consulta disponible/);
  assert.match(sync(lastRead), /2026-11-17T22:00:00\.000Z/);
});

test('navigation labels the current section and integrates future destinations without controls', () => {
  const event = { id: 'authorized', name: 'Festival', venue: 'Recinto', status: 'ACTIVE' };
  const html = renderToStaticMarkup(createElement(OrganizerLayout, {
    event, section: 'estadisticas', hash: '#estadisticas', available: [event],
  }, createElement(PendingSection, { section: 'estadisticas' })));
  assert.match(html, /href="#estadisticas" aria-current="page"/);
  for (const label of ['Dashboard', 'Incidentes', 'Recinto y zonas', 'Personal', 'Anuncios y promociones']) {
    assert.ok(html.includes(label));
  }
  assert.match(html, /Festival/);
  assert.match(html, /Activo/);
  assert.doesNotMatch(html, /<select/);
  for (const section of ['personal', 'estadisticas', 'anuncios']) {
    const placeholder = renderToStaticMarkup(createElement(PendingSection, { section }));
    assert.match(placeholder, /En desarrollo/);
    assert.doesNotMatch(placeholder, /<(a |button|input|select)/);
  }
  assert.equal(sectionForHash(queueHref), 'incidentes');
  assert.equal(sectionForHash(historyHref), 'incidentes');
  assert.equal(sectionForHash('#otro-evento'), 'dashboard');
});

test('post-command refresh discards an in-flight snapshot from before the write', async () => {
  const requests = [];
  const snapshots = [];
  const polling = incidentPolling(signal => new Promise(resolve => requests.push({ signal, resolve })),
    snapshot => snapshots.push(snapshot), () => false);
  try {
    polling.setVisible(true);
    polling.invalidate();
    assert.equal(requests.length, 2);
    assert.equal(requests[0].signal.aborted, true);
    requests[0].resolve('pre-write');
    requests[1].resolve('post-write');
    await Promise.resolve();
    assert.deepEqual(snapshots, ['post-write']);
  } finally {
    polling.setVisible(false);
  }
});

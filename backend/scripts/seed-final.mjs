import { closeSync, mkdirSync, openSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { dirname, resolve } from 'node:path';
import { migrate, openDb, atomic } from '../dist/src/db.js';
import { seed } from '../dist/src/seed.js';
import { issueTicket, savePromotion } from '../dist/src/event-services.js';
import { initializeTriage } from '../dist/src/triage.js';

const input = process.argv[2];
if (!input || !input.endsWith('.sqlite')) throw new Error('Proporciona una ruta nueva con extensión .sqlite');
const path = resolve(input);
mkdirSync(dirname(path), { recursive: true });
closeSync(openSync(path, 'wx'));
const db = openDb(path);
const event = 'demo-festival';
const organizer = { personId: 'demo-organizador', role: 'ORGANIZER' };
const minute = 60_000;
const now = Date.now();
const date = ms => new Date(ms).toISOString();
try {
  migrate(db);
  seed(db);
  atomic(db, () => {
    db.prepare('UPDATE events SET venue = ?, venue_description = ?, starts_at = ?, ends_at = ? WHERE id = ?')
      .run('Parque Ficticio', 'Recinto de la demostración universitaria', date(now - 60 * minute), date(now + 480 * minute), event);
    const catalog = db.prepare('UPDATE zones SET name = ?, description = ?, kind = ? WHERE event_id = ? AND id = ?');
    catalog.run('Entrada principal', 'Junto al toro mecánico y la puerta morada.', 'ACCESS', event, 'acceso');
    catalog.run('Escenario', 'Frente a la estatua plateada y las banderas turquesa.', 'STAGE', event, 'escenario');
    catalog.run('Zona de comida', 'Mesas junto al letrero amarillo de comida.', 'SERVICES', event, 'comida');
    db.prepare(`INSERT INTO operational_points (id, event_id, zone_id, name, kind, reference, active)
      VALUES (?, ?, 'acceso', 'Puesto médico demo', 'MEDICAL', 'Bajo el toldo morado', 1)`).run(randomUUID(), event);
    const insert = db.prepare(`INSERT INTO incidents (id, event_id, requester_person_id, zone_id, status, client_request_id,
      created_at, updated_at, version, assigned_staff_id, assigned_at, started_at, resolved_at, resolution_note, reason_code)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`);
    const transition = db.prepare('INSERT INTO incident_transitions VALUES (?, ?, ?, ?, ?, ?, ?, ?)');
    for (let index = 0; index < 3; index++) {
      const id = randomUUID();
      const created = now - (index + 1) * 24 * 60 * minute;
      const resolved = index !== 2;
      const assignee = resolved ? 'demo-guardia' : null;
      const person = ['demo-martin', 'demo-sofia', 'demo-antonia'][index];
      const end = date(created + (index + 5) * minute);
      const assigned = resolved ? date(created + minute) : null;
      const started = resolved ? date(created + 2 * minute) : null;
      const reason = ['ILLNESS_OR_INJURY', 'OTHER', 'CROWDING'][index];
      insert.run(id, event, person, ['acceso', 'comida', 'escenario'][index], resolved ? 'RESOLVED' : 'CANCELLED',
        randomUUID(), date(created), end, resolved ? 4 : 2, assignee, assigned, started, resolved ? end : null,
        resolved ? 'Atención histórica ficticia sembrada para la demostración.' : null,
        reason);
      initializeTriage(db, id, created, false, reason, null);
      if (!resolved) {
        transition.run(id, 2, person, 'OPEN', 'CANCELLED', end, null, null);
        continue;
      }
      transition.run(id, 2, assignee, 'OPEN', 'ASSIGNED', assigned, randomUUID(), 'semilla-demo');
      transition.run(id, 3, assignee, 'ASSIGNED', 'IN_PROGRESS', started, randomUUID(), 'semilla-demo');
      transition.run(id, 4, assignee, 'IN_PROGRESS', 'RESOLVED', end, randomUUID(), 'semilla-demo');
    }
  });
  issueTicket(db, organizer, event, { attendeeId: 'demo-antonia', expiresAt: date(now + 480 * minute) });
  savePromotion(db, organizer, event, { title: 'Agua en el sector de comida',
    description: 'Promoción ficticia del Festival Demo. Disponible durante la demostración.',
    imageUrl: null, linkUrl: null, startsAt: date(now - minute), endsAt: date(now + 480 * minute), active: true });
  if (db.prepare('PRAGMA foreign_key_check').all().length) throw new Error('Fallo de integridad');
  console.log('Base de demostración creada en ' + path);
} finally {
  db.close();
}

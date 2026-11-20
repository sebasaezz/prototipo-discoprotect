import { atomic } from '../dist/src/db.js';

export function savePerrysStage(db) {
  const eventId = 'demo-festival';
  const id = 'entrada-perrys-stage';
  return atomic(db, () => {
    const event = db.prepare('SELECT config_version AS version FROM events WHERE id = ?').get(eventId);
    if (!event) throw new Error('La base no contiene el evento demo-festival');

    const previous = db.prepare('SELECT active FROM zones WHERE event_id = ? AND id = ?').get(eventId, id);
    const { changes } = db.prepare(`INSERT INTO zones (event_id, id, name, kind, description, reference, active)
      VALUES (?, ?, ?, 'ACCESS', ?, ?, 1)
      ON CONFLICT (event_id, id) DO UPDATE SET name = excluded.name, kind = excluded.kind,
        description = excluded.description, reference = excluded.reference, active = excluded.active
      WHERE zones.name IS NOT excluded.name OR zones.kind IS NOT excluded.kind
        OR zones.description IS NOT excluded.description OR zones.reference IS NOT excluded.reference
        OR zones.active IS NOT excluded.active`).run(
      eventId, id, "Entrada Perry's Stage",
      "Entrada al sector Perry's Stage. En este acceso hay un letrero morado gigante con forma de rayo, visible desde lejos. El letrero tiene forma de relámpago y sirve como referencia visual para identificar esta entrada.",
      'Letrero morado gigante con forma de rayo.',
    );
    if (!changes) return { action: null, version: event.version };

    const action = !previous ? 'CREATE' : previous.active === 0 ? 'ACTIVATE' : 'UPDATE';
    const version = event.version + 1;
    db.prepare('UPDATE events SET config_version = ? WHERE id = ?').run(version, eventId);
    db.prepare(`INSERT INTO venue_configuration_changes
      (event_id, revision, actor_person_id, changed_at, entity, entity_id, action)
      VALUES (?, ?, NULL, ?, 'ZONE', ?, ?)`).run(eventId, version, new Date().toISOString(), id, action);
    return { action, version };
  });
}

import { readFileSync } from 'node:fs';
import { boundarySchema, withinBoundary } from '../dist/src/event-map.js';
import { atomic } from '../dist/src/db.js';

export const parqueOhiggins = boundarySchema.parse(JSON.parse(readFileSync(
  new URL('../fixtures/parque-ohiggins-osm-14817188.geojson', import.meta.url), 'utf8',
)));
const event = 'demo-festival';

export function configureLollapaloozaMap(db, medicalPointId) {
  atomic(db, () => {
    db.prepare(`UPDATE events SET name = ?, venue = ?, venue_description = ?, starts_at = ?, ends_at = ?
      WHERE id = ?`).run('Lollapalooza Chile 2027', "Parque O'Higgins",
      'Recinto del prototipo académico en Santiago de Chile. Los marcadores de operación están simulados.',
      '2027-03-12T03:00:00.000Z', '2027-03-15T03:00:00.000Z', event);
    const landmarks = [{ id: 'osm-way-32864171', name: 'Santander Arena',
      description: 'Antes Movistar Arena. Referencia geográfica del Parque O’Higgins.',
      latitude: -33.4627265, longitude: -70.6619440 }];
    db.prepare(`INSERT INTO event_maps (event_id, boundary_json, source_url, city, demo_enabled, landmarks_json)
      VALUES (?, ?, ?, ?, 1, ?)`).run(event, JSON.stringify(parqueOhiggins),
      'https://www.openstreetmap.org/way/14817188', 'Santiago de Chile', JSON.stringify(landmarks));
    const zone = db.prepare(`INSERT INTO zone_map_positions (event_id, zone_id, latitude, longitude, simulated)
      VALUES (?, ?, ?, ?, 1)`);
    const point = db.prepare(`INSERT INTO public_point_map_positions (event_id, point_id, latitude, longitude, simulated)
      VALUES (?, ?, ?, ?, 1)`);
    // Provisional positions for the existing fictional catalog, inside the actual park polygon.
    for (const [id, longitude, latitude] of [
      ['entrada-perrys-stage', -70.66010, -33.46175],
      ['acceso', -70.65795, -33.46347],
      ['escenario', -70.66175, -33.46573],
      ['comida', -70.66073, -33.46690],
    ]) {
      if (!withinBoundary(parqueOhiggins, [longitude, latitude])) throw new Error('Marcador demo fuera del parque: ' + id);
      zone.run(event, id, latitude, longitude);
    }
    if (medicalPointId) {
      const longitude = -70.65905;
      const latitude = -33.46437;
      if (!withinBoundary(parqueOhiggins, [longitude, latitude])) throw new Error('Puesto demo fuera del parque');
      point.run(event, medicalPointId, latitude, longitude);
    }
  });
}

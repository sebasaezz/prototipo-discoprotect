import { z } from 'zod';
import type { Db } from './db.js';
import { ApiError } from './errors.js';
import { venuePoints, venueZones } from './venue.js';

const latitude = z.number().min(-90).max(90);
const longitude = z.number().min(-180).max(180);

export const geoLocationInput = z.strictObject({
  latitude, longitude,
  accuracyMeters: z.number().nonnegative().nullish().transform(value => value ?? null),
  source: z.enum(['GPS', 'MANUAL', 'DEMO']),
  capturedAt: z.iso.datetime({ offset: true }).refine(value => Number.isFinite(Date.parse(value)))
    .transform(value => new Date(value).toISOString()),
}).refine(value => value.source === 'GPS' || value.accuracyMeters === null);

export type GeoLocation = z.output<typeof geoLocationInput>;
type Coordinate = [number, number];
const landmarksSchema = z.array(z.object({ id: z.string().min(1), name: z.string().min(1),
  description: z.string(), latitude, longitude }));
const ring = z.array(z.tuple([longitude, latitude])).min(4).refine(points => {
  const [x, y] = points[0];
  return points.at(-1)![0] === x && points.at(-1)![1] === y &&
    points.slice(1).reduce((area, [lon, lat], index) =>
      area + (points[index][0] - x) * (lat - y) - (lon - x) * (points[index][1] - y), 0) !== 0;
});
export const boundarySchema = z.strictObject({ type: z.literal('Polygon'), coordinates: z.array(ring).min(1) });
export type Boundary = z.infer<typeof boundarySchema>;

function ringPosition([x, y]: Coordinate, points: Coordinate[]) {
  let inside = false;
  for (let i = 1; i < points.length; i++) {
    const [ax, ay] = points[i - 1];
    const [bx, by] = points[i];
    const tolerance = 1e-10;
    if (Math.abs((x - ax) * (by - ay) - (y - ay) * (bx - ax)) <= tolerance * Math.hypot(bx - ax, by - ay) &&
        x >= Math.min(ax, bx) - tolerance && x <= Math.max(ax, bx) + tolerance &&
        y >= Math.min(ay, by) - tolerance && y <= Math.max(ay, by) + tolerance) return 'EDGE';
    if ((ay > y) !== (by > y) && x < ax + (bx - ax) * (y - ay) / (by - ay)) inside = !inside;
  }
  return inside ? 'INSIDE' : 'OUTSIDE';
}

export function withinBoundary(boundary: Boundary, point: Coordinate) {
  if (!longitude.safeParse(point[0]).success || !latitude.safeParse(point[1]).success) return false;
  const [exterior, ...holes] = boundary.coordinates;
  return ringPosition(point, exterior) !== 'OUTSIDE' && holes.every(hole => ringPosition(point, hole) !== 'INSIDE');
}

function readConfiguration(db: Db, eventId: string) {
  const row = db.prepare(`SELECT m.boundary_json AS boundary, m.source_url AS sourceUrl, m.city, m.demo_enabled AS demoEnabled, m.landmarks_json AS landmarks,
    e.name, e.venue, e.starts_at AS startsAt, e.ends_at AS endsAt, e.config_version AS revision FROM event_maps m
    JOIN events e ON e.id = m.event_id WHERE m.event_id = ?`).get(eventId) as
    { boundary: string; sourceUrl: string; city: string; demoEnabled: number; landmarks: string; name: string; venue: string;
      startsAt: string; endsAt: string; revision: number } | undefined;
  if (!row) return null;
  const { landmarks, boundary, demoEnabled, ...configuration } = row;
  return { ...configuration, demoEnabled: demoEnabled === 1,
    boundary: boundarySchema.parse(JSON.parse(boundary)), landmarks: landmarksSchema.parse(JSON.parse(landmarks)) };
}

type MapPosition = { latitude: number; longitude: number; simulated: boolean };

function positions(db: Db, eventId: string, table: 'zones' | 'points') {
  const sql = table === 'zones'
    ? 'SELECT zone_id AS id, latitude, longitude, simulated FROM zone_map_positions WHERE event_id = ?'
    : 'SELECT point_id AS id, latitude, longitude, simulated FROM public_point_map_positions WHERE event_id = ?';
  const rows = db.prepare(sql).all(eventId) as { id: string; latitude: number; longitude: number; simulated: number }[];
  return new Map<string, MapPosition>(rows.map(({ id, simulated, ...row }) => [id, { ...row, simulated: simulated === 1 }]));
}

export function eventMap(db: Db, eventId: string) {
  const map = readConfiguration(db, eventId);
  if (!map) return { eventId, map: null };
  const zonePositions = positions(db, eventId, 'zones');
  const pointPositions = positions(db, eventId, 'points');
  const validPosition = (position: MapPosition | undefined) =>
    position && withinBoundary(map.boundary, [position.longitude, position.latitude]) ? position : null;
  const zones = venueZones(db, eventId).map(({ active, ...zone }) =>
    ({ ...zone, position: validPosition(zonePositions.get(zone.id)) }));
  const points = venuePoints(db, eventId).flatMap(({ active, zoneName, ...point }) => {
    const position = validPosition(pointPositions.get(point.id));
    return position ? [{ ...point, position }] : [];
  });
  const landmarks = map.landmarks.flatMap(({ latitude, longitude, ...place }) => {
    const position = validPosition({ latitude, longitude, simulated: false });
    return position ? [{ ...place, kind: 'LANDMARK', position }] : [];
  });
  const [outline] = map.boundary.coordinates;
  const xmin = Math.min(...outline.map(point => point[0]));
  const xmax = Math.max(...outline.map(point => point[0]));
  const ymin = Math.min(...outline.map(point => point[1]));
  const ymax = Math.max(...outline.map(point => point[1]));
  return { eventId, map: { ...map, bounds: [xmin, ymin, xmax, ymax],
    center: [(xmin + xmax) / 2, (ymin + ymax) / 2], zones, points, landmarks } };
}

export function validateIncidentLocation(db: Db, eventId: string, location: GeoLocation) {
  if (location.source === 'GPS') return;
  const map = readConfiguration(db, eventId);
  if (!map || (location.source === 'DEMO' && !map.demoEnabled) ||
      !withinBoundary(map.boundary, [location.longitude, location.latitude])) {
    throw new ApiError(400, 'INVALID_INPUT', 'Selecciona un punto dentro del recinto habilitado');
  }
}

export function sameGeoLocation(first: GeoLocation | null, second: GeoLocation | null) {
  if (!first || !second) return first === second;
  return first.latitude === second.latitude && first.longitude === second.longitude &&
    first.accuracyMeters === second.accuracyMeters && first.source === second.source && first.capturedAt === second.capturedAt;
}

CREATE TABLE event_maps (
  event_id TEXT PRIMARY KEY REFERENCES events(id) ON DELETE CASCADE,
  boundary_json TEXT NOT NULL CHECK (json_valid(boundary_json)),
  source_url TEXT NOT NULL,
  city TEXT NOT NULL,
  landmarks_json TEXT NOT NULL DEFAULT '[]' CHECK (json_valid(landmarks_json)),
  demo_enabled INTEGER NOT NULL DEFAULT 0 CHECK (demo_enabled IN (0, 1))
) STRICT;

CREATE TABLE zone_map_positions (
  event_id TEXT NOT NULL,
  zone_id TEXT NOT NULL,
  latitude REAL NOT NULL CHECK (latitude BETWEEN -90 AND 90),
  longitude REAL NOT NULL CHECK (longitude BETWEEN -180 AND 180),
  simulated INTEGER NOT NULL CHECK (simulated IN (0, 1)),
  PRIMARY KEY (event_id, zone_id),
  FOREIGN KEY (event_id) REFERENCES event_maps(event_id) ON DELETE CASCADE,
  FOREIGN KEY (event_id, zone_id) REFERENCES zones(event_id, id) ON DELETE CASCADE
) STRICT;

CREATE TABLE public_point_map_positions (
  event_id TEXT NOT NULL,
  point_id TEXT NOT NULL,
  latitude REAL NOT NULL CHECK (latitude BETWEEN -90 AND 90),
  longitude REAL NOT NULL CHECK (longitude BETWEEN -180 AND 180),
  simulated INTEGER NOT NULL CHECK (simulated IN (0, 1)),
  PRIMARY KEY (event_id, point_id),
  FOREIGN KEY (event_id) REFERENCES event_maps(event_id) ON DELETE CASCADE,
  FOREIGN KEY (event_id, point_id) REFERENCES operational_points(event_id, id) ON DELETE CASCADE
) STRICT;

CREATE UNIQUE INDEX incidents_event_id_id ON incidents(event_id, id);

CREATE TABLE incident_geo_locations (
  incident_id TEXT PRIMARY KEY,
  event_id TEXT NOT NULL,
  latitude REAL NOT NULL CHECK (latitude BETWEEN -90 AND 90),
  longitude REAL NOT NULL CHECK (longitude BETWEEN -180 AND 180),
  accuracy_meters REAL CHECK (accuracy_meters >= 0),
  source TEXT NOT NULL CHECK (source IN ('GPS', 'MANUAL', 'DEMO')),
  captured_at TEXT NOT NULL,
  FOREIGN KEY (event_id, incident_id) REFERENCES incidents(event_id, id) ON DELETE CASCADE
) STRICT;

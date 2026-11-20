ALTER TABLE events ADD COLUMN venue_description TEXT CHECK (length(venue_description) BETWEEN 1 AND 600);
ALTER TABLE events ADD COLUMN access_notes TEXT CHECK (length(access_notes) BETWEEN 1 AND 300);
ALTER TABLE events ADD COLUMN response_notes TEXT CHECK (length(response_notes) BETWEEN 1 AND 300);
ALTER TABLE events ADD COLUMN config_version INTEGER NOT NULL DEFAULT 1 CHECK (config_version > 0);

ALTER TABLE zones ADD COLUMN description TEXT CHECK (length(description) BETWEEN 1 AND 240);
ALTER TABLE zones ADD COLUMN kind TEXT NOT NULL DEFAULT 'OTHER'
  CHECK (kind IN ('STAGE', 'FLOOR', 'ACCESS', 'STANDS', 'SERVICES', 'OTHER'));
ALTER TABLE zones ADD COLUMN reference TEXT CHECK (length(reference) BETWEEN 1 AND 160);
ALTER TABLE zones ADD COLUMN active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1));

CREATE TABLE operational_points (
  event_id TEXT NOT NULL,
  id TEXT NOT NULL,
  zone_id TEXT NOT NULL,
  name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 160),
  kind TEXT NOT NULL CHECK (kind IN ('MEDICAL', 'SECURITY', 'ENTRANCE', 'EXIT', 'MEETING')),
  reference TEXT NOT NULL CHECK (length(trim(reference)) BETWEEN 1 AND 160),
  active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
  PRIMARY KEY (event_id, id),
  FOREIGN KEY (event_id) REFERENCES events(id) ON DELETE CASCADE,
  FOREIGN KEY (event_id, zone_id) REFERENCES zones(event_id, id) ON DELETE CASCADE
) STRICT;
CREATE INDEX operational_points_by_zone ON operational_points(event_id, zone_id, active);

CREATE TABLE venue_configuration_changes (
  event_id TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  revision INTEGER NOT NULL CHECK (revision > 1),
  actor_person_id TEXT REFERENCES people(id) ON DELETE SET NULL,
  changed_at TEXT NOT NULL,
  entity TEXT NOT NULL CHECK (entity IN ('VENUE', 'ZONE', 'POINT')),
  entity_id TEXT NOT NULL,
  action TEXT NOT NULL CHECK (action IN ('CREATE', 'UPDATE', 'ACTIVATE', 'DEACTIVATE')),
  PRIMARY KEY (event_id, revision)
) STRICT;

ALTER TABLE incident_chat_ai_runs ADD COLUMN inferred_zone_name TEXT;
ALTER TABLE incident_chat_ai_runs ADD COLUMN venue_revision INTEGER CHECK (venue_revision > 0);
UPDATE incident_chat_ai_runs AS r
SET inferred_zone_name = (
  SELECT z.name FROM incidents i JOIN zones z ON z.event_id = i.event_id
  WHERE i.id = r.incident_id AND z.id = json_extract(r.result_json, '$.zoneId')
)
WHERE r.status = 'COMPLETE' AND r.result_json IS NOT NULL;

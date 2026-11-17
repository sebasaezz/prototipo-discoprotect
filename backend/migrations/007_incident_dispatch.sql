CREATE TABLE incident_dispatch_reviews (
  incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
  event_id TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  revision INTEGER NOT NULL CHECK (revision > 0),
  operation_id TEXT NOT NULL,
  request_hash TEXT NOT NULL,
  actor_person_id TEXT NOT NULL REFERENCES people(id),
  reviewed_at TEXT NOT NULL,
  human_priority TEXT CHECK (human_priority IN ('P0','P1','P2','P3','PENDING')),
  confirmed_zone_id TEXT,
  confirmed_text TEXT CHECK (length(confirmed_text) BETWEEN 1 AND 160),
  danger_assessment TEXT NOT NULL CHECK (danger_assessment IN ('UNCHANGED','CONFIRMED','CLEARED')),
  reason TEXT NOT NULL CHECK (length(reason) BETWEEN 1 AND 240),
  PRIMARY KEY (incident_id, revision),
  UNIQUE (incident_id, operation_id),
  FOREIGN KEY (event_id, confirmed_zone_id) REFERENCES zones(event_id, id)
) STRICT;

CREATE TABLE dispatch_priority_policies (
  version TEXT PRIMARY KEY,
  early_seconds INTEGER NOT NULL CHECK (early_seconds > 0),
  late_seconds INTEGER NOT NULL CHECK (late_seconds > early_seconds),
  activated_at TEXT NOT NULL
) STRICT;

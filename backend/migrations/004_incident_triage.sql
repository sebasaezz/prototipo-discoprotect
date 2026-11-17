ALTER TABLE incidents ADD COLUMN reason_code TEXT CHECK (reason_code IN (
  'UNRESPONSIVE', 'IMMEDIATE_DANGER', 'ILLNESS_OR_INJURY', 'AGGRESSION_OR_HARASSMENT', 'CROWDING', 'OTHER'
));
ALTER TABLE incidents ADD COLUMN description TEXT;

CREATE TABLE incident_triage (
  incident_id TEXT PRIMARY KEY REFERENCES incidents(id) ON DELETE CASCADE,
  status TEXT NOT NULL CHECK (status IN ('PENDING', 'SUGGESTED', 'MANUAL')),
  version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
  fallback_reason TEXT,
  result_json TEXT CHECK (json_valid(result_json)),
  queued_at INTEGER NOT NULL,
  attempted_at INTEGER,
  finished_at INTEGER,
  model TEXT,
  prompt_version TEXT,
  latency_ms INTEGER,
  input_tokens INTEGER,
  output_tokens INTEGER,
  CHECK ((status = 'MANUAL') = (fallback_reason IS NOT NULL)),
  CHECK ((status = 'SUGGESTED') = (result_json IS NOT NULL))
) STRICT;

CREATE INDEX incident_triage_pending ON incident_triage(queued_at, incident_id) WHERE status = 'PENDING';

INSERT INTO incident_triage (incident_id, status, fallback_reason, queued_at, finished_at)
SELECT id, 'MANUAL', 'LEGACY', 0, 0 FROM incidents;

CREATE TABLE incident_triage_reviews (
  incident_id TEXT NOT NULL REFERENCES incident_triage(incident_id) ON DELETE CASCADE,
  applied_version INTEGER NOT NULL CHECK (applied_version > 1),
  actor_person_id TEXT NOT NULL REFERENCES people(id),
  operation_id TEXT NOT NULL,
  request_hash TEXT NOT NULL,
  decision TEXT NOT NULL CHECK (decision IN ('CONFIRM', 'CORRECT')),
  category TEXT NOT NULL CHECK (category IN ('MEDICAL', 'SECURITY', 'CROWD', 'OTHER', 'UNKNOWN')),
  urgency TEXT NOT NULL CHECK (urgency IN ('IMMEDIATE', 'PRIORITY', 'UNDETERMINED')),
  reviewed_at TEXT NOT NULL,
  PRIMARY KEY (incident_id, applied_version),
  UNIQUE (incident_id, operation_id)
) STRICT;

CREATE TABLE triage_daily_usage (
  day_utc TEXT PRIMARY KEY,
  reserved INTEGER NOT NULL CHECK (reserved > 0)
) STRICT;

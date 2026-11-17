CREATE TABLE incident_chat_ai_runs (
  incident_id TEXT NOT NULL REFERENCES incident_conversations(incident_id) ON DELETE CASCADE,
  input_seq INTEGER NOT NULL,
  queued_at INTEGER NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('QUEUED','RUNNING','COMPLETE','MANUAL','DISCARDED')),
  reserved_at INTEGER,
  finished_at INTEGER,
  reason TEXT,
  result_json TEXT CHECK (json_valid(result_json)),
  message_seq INTEGER,
  model TEXT,
  prompt_version TEXT,
  input_tokens INTEGER,
  output_tokens INTEGER,
  PRIMARY KEY (incident_id, input_seq),
  FOREIGN KEY (incident_id, input_seq) REFERENCES incident_messages(incident_id, seq) ON DELETE CASCADE,
  FOREIGN KEY (incident_id, message_seq) REFERENCES incident_messages(incident_id, seq) ON DELETE CASCADE,
  CHECK ((status = 'COMPLETE') = (result_json IS NOT NULL))
) STRICT;

CREATE INDEX incident_chat_ai_queue ON incident_chat_ai_runs(queued_at, incident_id, input_seq) WHERE status = 'QUEUED';

CREATE TABLE zone_aliases (
  event_id TEXT NOT NULL,
  zone_id TEXT NOT NULL,
  alias TEXT NOT NULL CHECK (length(alias) BETWEEN 1 AND 80),
  PRIMARY KEY (event_id, zone_id, alias),
  FOREIGN KEY (event_id, zone_id) REFERENCES zones(event_id, id) ON DELETE CASCADE
) STRICT;

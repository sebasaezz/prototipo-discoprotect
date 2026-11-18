CREATE TABLE incident_chat_observations (
  incident_id TEXT NOT NULL,
  input_seq INTEGER NOT NULL,
  facts_json TEXT NOT NULL CHECK (json_valid(facts_json)),
  reply_seq INTEGER,
  question TEXT CHECK (question IN ('BREATHING','RESPONSIVENESS','SCENE','AFFECTED','LANDMARK','HELP')),
  protocol TEXT,
  PRIMARY KEY (incident_id, input_seq),
  UNIQUE (incident_id, reply_seq),
  FOREIGN KEY (incident_id, input_seq) REFERENCES incident_messages(incident_id, seq) ON DELETE CASCADE,
  FOREIGN KEY (incident_id, reply_seq) REFERENCES incident_messages(incident_id, seq) ON DELETE CASCADE
) STRICT;

ALTER TABLE incident_dispatch_reviews ADD COLUMN observed_attendee_seq INTEGER;

CREATE TABLE chat_ai_daily_usage (
  day TEXT PRIMARY KEY,
  reserved INTEGER NOT NULL CHECK (reserved >= 0)
) STRICT;

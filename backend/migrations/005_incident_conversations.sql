CREATE TABLE incident_conversations (
  incident_id TEXT PRIMARY KEY REFERENCES incidents(id) ON DELETE CASCADE,
  creation_mode TEXT NOT NULL CHECK (creation_mode IN ('CHAT', 'SOS')),
  created_at TEXT NOT NULL,
  last_seq INTEGER NOT NULL DEFAULT 0 CHECK (last_seq >= 0),
  last_attendee_seq INTEGER NOT NULL DEFAULT 0 CHECK (last_attendee_seq BETWEEN 0 AND last_seq)
) STRICT;

CREATE TABLE incident_messages (
  id TEXT PRIMARY KEY,
  incident_id TEXT NOT NULL REFERENCES incident_conversations(incident_id) ON DELETE CASCADE,
  seq INTEGER NOT NULL CHECK (seq > 0),
  author_type TEXT NOT NULL CHECK (author_type IN ('ATTENDEE', 'AI', 'STAFF')),
  author_person_id TEXT REFERENCES people(id),
  body TEXT NOT NULL CHECK (length(body) BETWEEN 1 AND 500),
  created_at TEXT NOT NULL,
  client_message_id TEXT,
  request_hash TEXT,
  UNIQUE (incident_id, seq),
  UNIQUE (incident_id, client_message_id),
  CHECK ((author_type = 'AI') = (author_person_id IS NULL)),
  CHECK ((author_type = 'AI') = (client_message_id IS NULL)),
  CHECK ((author_type = 'AI') = (request_hash IS NULL))
) STRICT;

CREATE INDEX incident_messages_by_author ON incident_messages(incident_id, author_person_id, created_at);

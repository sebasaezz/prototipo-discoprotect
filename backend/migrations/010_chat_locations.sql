CREATE TABLE incident_chat_locations (
  incident_id TEXT NOT NULL,
  seq INTEGER NOT NULL,
  event_id TEXT NOT NULL,
  zone_id TEXT NOT NULL,
  zone_name TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('PROPOSED','CONFIRMED','INDICATED','REJECTED','DISMISSED','MOVED')),
  proposal_seq INTEGER,
  evidence_seq INTEGER NOT NULL,
  evidence TEXT NOT NULL,
  feature TEXT,
  venue_revision INTEGER NOT NULL CHECK (venue_revision > 0),
  PRIMARY KEY (incident_id, seq),
  FOREIGN KEY (incident_id, seq) REFERENCES incident_messages(incident_id, seq) ON DELETE CASCADE,
  FOREIGN KEY (incident_id, proposal_seq) REFERENCES incident_messages(incident_id, seq) ON DELETE CASCADE,
  FOREIGN KEY (incident_id, evidence_seq) REFERENCES incident_messages(incident_id, seq) ON DELETE CASCADE,
  FOREIGN KEY (event_id, zone_id) REFERENCES zones(event_id, id),
  CHECK (proposal_seq IS NULL OR proposal_seq < seq)
) STRICT;

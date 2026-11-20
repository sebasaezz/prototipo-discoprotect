CREATE TABLE incident_local_locations (
    incident_id TEXT NOT NULL,
    input_seq INTEGER NOT NULL,
    zone_id TEXT,
    zone_name TEXT,
    quote TEXT NOT NULL,
    feature TEXT,
    venue_version INTEGER NOT NULL,
    PRIMARY KEY (incident_id, input_seq),
    CHECK ((zone_id IS NULL) = (zone_name IS NULL)),
    FOREIGN KEY (incident_id, input_seq) REFERENCES incident_messages(incident_id, seq) ON DELETE CASCADE
) STRICT;

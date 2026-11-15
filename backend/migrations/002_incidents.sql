CREATE TABLE zones (
    event_id TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    id TEXT NOT NULL,
    name TEXT NOT NULL CHECK (length(trim(name)) > 0),
    PRIMARY KEY (event_id, id)
) STRICT;

CREATE TABLE incidents (
    id TEXT PRIMARY KEY,
    event_id TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    requester_person_id TEXT NOT NULL REFERENCES people(id) ON DELETE CASCADE,
    zone_id TEXT,
    location_text TEXT CHECK (length(location_text) BETWEEN 1 AND 160),
    status TEXT NOT NULL CHECK (status IN ('OPEN', 'CANCELLED')),
    client_request_id TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL CHECK (updated_at >= created_at),
    UNIQUE (requester_person_id, client_request_id),
    FOREIGN KEY (event_id, zone_id) REFERENCES zones(event_id, id)
) STRICT;
CREATE INDEX incidents_by_event ON incidents(event_id, created_at DESC, id DESC);
CREATE INDEX incidents_by_requester ON incidents(requester_person_id, event_id, created_at DESC, id DESC);
CREATE INDEX incidents_by_zone ON incidents(event_id, zone_id);

CREATE TABLE incidents_new (
    id TEXT PRIMARY KEY,
    event_id TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    requester_person_id TEXT NOT NULL REFERENCES people(id) ON DELETE CASCADE,
    zone_id TEXT,
    location_text TEXT CHECK (length(location_text) BETWEEN 1 AND 160),
    status TEXT NOT NULL CHECK (status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CANCELLED')),
    client_request_id TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL CHECK (updated_at >= created_at),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    assigned_staff_id TEXT REFERENCES people(id),
    assigned_at TEXT CHECK (assigned_at BETWEEN created_at AND updated_at),
    started_at TEXT CHECK (started_at BETWEEN assigned_at AND updated_at),
    resolved_at TEXT CHECK (resolved_at BETWEEN started_at AND updated_at),
    resolution_note TEXT CHECK (resolution_note IS NULL OR (status = 'RESOLVED' AND length(resolution_note) BETWEEN 1 AND 240)),
    CHECK ((assigned_staff_id IS NOT NULL) = (status IN ('ASSIGNED', 'IN_PROGRESS', 'RESOLVED'))),
    CHECK ((assigned_at IS NOT NULL) = (assigned_staff_id IS NOT NULL)),
    CHECK ((started_at IS NOT NULL) = (status IN ('IN_PROGRESS', 'RESOLVED'))),
    CHECK ((resolved_at IS NOT NULL) = (status = 'RESOLVED')),
    UNIQUE (requester_person_id, client_request_id),
    FOREIGN KEY (event_id, zone_id) REFERENCES zones(event_id, id)
) STRICT;

INSERT INTO incidents_new (id, event_id, requester_person_id, zone_id, location_text,
    status, client_request_id, created_at, updated_at)
SELECT id, event_id, requester_person_id, zone_id, location_text,
    status, client_request_id, created_at, updated_at FROM incidents;

DROP TABLE incidents;
ALTER TABLE incidents_new RENAME TO incidents;
CREATE INDEX incidents_by_event ON incidents(event_id, created_at DESC, id DESC);
CREATE INDEX incidents_by_requester ON incidents(requester_person_id, event_id, created_at DESC, id DESC);
CREATE INDEX incidents_by_zone ON incidents(event_id, zone_id);

CREATE TABLE incident_transitions (
    incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    applied_version INTEGER NOT NULL CHECK (applied_version > 1),
    actor_person_id TEXT NOT NULL REFERENCES people(id),
    from_status TEXT NOT NULL,
    to_status TEXT NOT NULL,
    applied_at TEXT NOT NULL,
    operation_id TEXT,
    request_hash TEXT,
    PRIMARY KEY (incident_id, applied_version),
    UNIQUE (incident_id, operation_id),
    CHECK ((from_status = 'OPEN' AND to_status IN ('ASSIGNED', 'CANCELLED'))
        OR (from_status = 'ASSIGNED' AND to_status = 'IN_PROGRESS')
        OR (from_status = 'IN_PROGRESS' AND to_status = 'RESOLVED')),
    CHECK ((operation_id IS NULL) = (to_status = 'CANCELLED')),
    CHECK ((request_hash IS NULL) = (operation_id IS NULL))
) STRICT;

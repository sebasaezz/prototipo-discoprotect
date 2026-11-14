CREATE TABLE people (
    id TEXT PRIMARY KEY,
    demo_key TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    age INTEGER,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
) STRICT;

CREATE TABLE emergency_cards (
    id TEXT PRIMARY KEY,
    person_id TEXT NOT NULL UNIQUE REFERENCES people(id) ON DELETE CASCADE,
    blood_type TEXT,
    allergies TEXT NOT NULL CHECK (json_type(allergies) = 'array'),
    conditions TEXT NOT NULL CHECK (json_type(conditions) = 'array'),
    medications TEXT NOT NULL CHECK (json_type(medications) = 'array'),
    updated_at TEXT NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0)
) STRICT;

CREATE TABLE emergency_contacts (
    id TEXT PRIMARY KEY,
    person_id TEXT NOT NULL REFERENCES emergency_cards(person_id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    relationship TEXT NOT NULL,
    phone TEXT NOT NULL
) STRICT;
CREATE INDEX contacts_by_person ON emergency_contacts(person_id);

CREATE TABLE nfc_credentials (
    id TEXT PRIMARY KEY,
    person_id TEXT NOT NULL UNIQUE REFERENCES people(id) ON DELETE CASCADE,
    token BLOB NOT NULL UNIQUE CHECK (length(token) = 16),
    active INTEGER NOT NULL CHECK (active IN (0, 1)),
    created_at TEXT NOT NULL
) STRICT;

CREATE TABLE events (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    venue TEXT NOT NULL,
    starts_at TEXT NOT NULL,
    ends_at TEXT NOT NULL CHECK (ends_at > starts_at),
    status TEXT NOT NULL CHECK (status IN ('PLANNED', 'ACTIVE', 'ENDED'))
) STRICT;

CREATE TABLE event_memberships (
    id TEXT PRIMARY KEY,
    event_id TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    person_id TEXT NOT NULL REFERENCES people(id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('ATTENDEE', 'STAFF', 'ORGANIZER')),
    UNIQUE (event_id, person_id)
) STRICT;
CREATE INDEX memberships_by_person ON event_memberships(person_id);
CREATE TABLE demo_sessions (
    token_hash TEXT PRIMARY KEY,
    person_id TEXT NOT NULL REFERENCES people(id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('ATTENDEE', 'STAFF', 'ORGANIZER')),
    expires_at INTEGER NOT NULL
) STRICT;
CREATE INDEX sessions_by_expiry ON demo_sessions(expires_at);

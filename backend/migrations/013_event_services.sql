CREATE TABLE event_tickets (
    id TEXT PRIMARY KEY,
    event_id TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    attendee_id TEXT NOT NULL REFERENCES people(id),
    code TEXT NOT NULL UNIQUE,
    expires_at TEXT NOT NULL,
    used_at TEXT,
    revoked_at TEXT,
    UNIQUE (event_id, attendee_id)
) STRICT;

CREATE TABLE ticket_validations (
    actor_person_id TEXT NOT NULL REFERENCES people(id),
    operation_id TEXT NOT NULL,
    event_id TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    code_hash TEXT NOT NULL,
    result TEXT NOT NULL CHECK (result IN ('VALID', 'USED', 'REVOKED', 'EXPIRED', 'OTHER_EVENT', 'INVALID')),
    applied_at TEXT NOT NULL,
    PRIMARY KEY (actor_person_id, operation_id)
) STRICT;

CREATE TABLE event_promotions (
    id TEXT PRIMARY KEY,
    event_id TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    title_key TEXT NOT NULL,
    description TEXT NOT NULL,
    image_url TEXT,
    link_url TEXT,
    starts_at TEXT NOT NULL,
    ends_at TEXT NOT NULL CHECK (ends_at > starts_at),
    active INTEGER NOT NULL CHECK (active IN (0, 1)),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version > 0),
    UNIQUE (event_id, title_key)
) STRICT;
CREATE INDEX promotions_by_event ON event_promotions(event_id, active, starts_at, ends_at);

CREATE TABLE companion_invites (
    id TEXT PRIMARY KEY,
    event_id TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    sender_id TEXT NOT NULL REFERENCES people(id),
    code_hash TEXT NOT NULL UNIQUE,
    expires_at TEXT NOT NULL,
    redeemed_by TEXT REFERENCES people(id),
    closed_at TEXT,
    CHECK (sender_id <> redeemed_by)
) STRICT;
CREATE INDEX companion_invites_by_sender ON companion_invites(event_id, sender_id, expires_at);
CREATE UNIQUE INDEX companion_pair_once ON companion_invites (event_id,
    min(sender_id, redeemed_by), max(sender_id, redeemed_by)) WHERE redeemed_by IS NOT NULL AND closed_at IS NULL;

CREATE TABLE companion_access_audit (
    event_id TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    incident_id TEXT NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    actor_person_id TEXT NOT NULL REFERENCES people(id),
    accessed_at TEXT NOT NULL
) STRICT;
CREATE INDEX companion_access_by_incident ON companion_access_audit(incident_id);

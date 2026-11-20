CREATE TABLE event_staff_audit (
    actor_person_id TEXT NOT NULL REFERENCES people(id),
    operation_id TEXT NOT NULL,
    event_id TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    staff_person_id TEXT NOT NULL REFERENCES people(id),
    active INTEGER NOT NULL CHECK (active IN (0, 1)),
    changed INTEGER NOT NULL CHECK (changed IN (0, 1)),
    replacement_person_id TEXT REFERENCES people(id),
    reassigned_incident_id TEXT REFERENCES incidents(id) ON DELETE SET NULL,
    applied_at TEXT NOT NULL,
    PRIMARY KEY (actor_person_id, operation_id)
) STRICT;
CREATE INDEX event_staff_audit_by_event ON event_staff_audit(event_id, applied_at);

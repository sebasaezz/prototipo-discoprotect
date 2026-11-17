import { useCallback, useEffect, useState } from 'react';
import { ApiError, events, messageFor, type Event, type Session } from './api.js';
import { DashboardOverview } from './DashboardOverview.js';
import { IncidentSync } from './IncidentSync.js';
import { IncidentAdministration } from './IncidentAdministration.js';
import { OrganizerLayout } from './OrganizerLayout.js';
import { resetSection, sectionForHash, type Section } from './organizerNavigation.js';
import { PendingSection } from './PendingSection.js';
import { useEventIncidents } from './useEventIncidents.js';
import { VenuePage } from './VenuePage.js';

export function OrganizerWorkspace({ session, onExpiry, onSignOut }: {
  session: Session;
  onExpiry: () => void;
  onSignOut: () => void;
}) {
  const [available, setAvailable] = useState<Event[]>([]);
  const [selected, setSelected] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  const [hash, setHash] = useState(() => window.location.hash);

  useEffect(() => {
    const onHashChange = () => setHash(window.location.hash);
    window.addEventListener('hashchange', onHashChange);
    onHashChange();
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  useEffect(() => {
    const request = new AbortController();
    events(session, AbortSignal.any([request.signal, AbortSignal.timeout(8000)])).then(result => {
      if (request.signal.aborted) return;
      setAvailable(result);
      setSelected(result.length === 1 ? result[0].id : '');
      setLoading(false);
    }).catch(failure => {
      if (request.signal.aborted) return;
      if (failure instanceof ApiError && failure.status === 401) onExpiry();
      else {
        setError(messageFor(failure));
        setLoading(false);
      }
    });
    return () => request.abort();
  }, [session, revision, onExpiry]);

  const event = available.find(option => option.id === selected);
  const section = sectionForHash(hash);
  const updateEventLabel = useCallback((id: string, { name, venue }: { name: string; venue: string }) => {
    setAvailable(current => current.map(option => option.id === id ? { ...option, name, venue } : option));
  }, []);

  return <OrganizerLayout event={event} section={section} hash={hash} available={available}
    onSignOut={onSignOut} onSelect={id => {
      resetSection();
      setHash(window.location.hash);
      setSelected(id);
    }}>
    {event ? <EventSections key={event.id} eventId={event.id} session={session} onExpiry={onExpiry}
      section={section} onVenueRead={updateEventLabel} />
      : <section className="panel workspace-message" aria-label="Acceso al evento">
        {loading && <p role="status">Consultando eventos autorizados…</p>}
        {error && <>
          <p className="notice" role="alert">{error}</p>
          <button type="button" className="secondary" onClick={() => {
            setLoading(true);
            setError('');
            setRevision(current => current + 1);
          }}>Volver a consultar</button>
        </>}
        {!loading && !error && <p>{available.length
          ? 'Selecciona el evento que quieres gestionar.'
          : 'La cuenta no tiene eventos autorizados.'}</p>}
      </section>}
  </OrganizerLayout>;
}

function EventSections({ eventId, session, onExpiry, section, onVenueRead }: {
  eventId: string;
  session: Session;
  onExpiry: () => void;
  section: Section;
  onVenueRead: (id: string, venue: { name: string; venue: string }) => void;
}) {
  const { items, lastRead, error, refresh, invalidate, onAccessLost } = useEventIncidents(session, eventId, onExpiry);

  return <>
    {(section === 'dashboard' || section === 'incidentes') &&
      <IncidentSync lastRead={lastRead} error={error} refresh={refresh} />}
    {section === 'dashboard' && items && <DashboardOverview items={items} />}
    {items && <IncidentAdministration visible={section === 'incidentes'} items={items}
      session={session} event={eventId} onExpiry={onExpiry} onAccessLost={onAccessLost} refresh={invalidate} />}
    {section === 'recinto' && <VenuePage eventId={eventId} session={session} onExpiry={onExpiry} onVenueRead={onVenueRead} />}
    {section !== 'dashboard' && section !== 'incidentes' && section !== 'recinto' && <PendingSection section={section} />}
  </>;
}

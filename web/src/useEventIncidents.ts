import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, currentIncidentSnapshot, incidents, messageFor, type Incident, type Session } from './api.js';
import { incidentPolling } from './incidentPolling.js';

type IncidentSnapshot = {
  items: Incident[] | null;
  lastRead: Date | null;
  error: string;
};

export function useEventIncidents(session: Session, eventId: string, onExpiry: () => void) {
  const [snapshot, setSnapshot] = useState<IncidentSnapshot>({ items: null, lastRead: null, error: '' });
  const reader = useRef<ReturnType<typeof incidentPolling<Incident[]>> | null>(null);
  const refresh = useCallback(() => reader.current?.refresh(), []);
  const invalidate = useCallback(() => reader.current?.invalidate(), []);
  const onAccessLost = useCallback(() => {
    if (!reader.current) return;
    setSnapshot({ items: null, lastRead: null, error: 'La cuenta no está autorizada para este evento.' });
    reader.current.invalidate();
  }, []);

  useEffect(() => {
    const polling = incidentPolling(signal => {
      if (Date.parse(session.expiresAt) <= Date.now()) throw new ApiError(401, 'La sesión venció.');
      return incidents(session, eventId, signal);
    }, result => {
      const lastRead = new Date();
      setSnapshot(current => ({
        items: currentIncidentSnapshot(current.items, result), lastRead, error: '',
      }));
    }, failure => {
      if (failure instanceof ApiError && failure.status === 401) {
        onExpiry();
        return false;
      }
      const error = messageFor(failure);
      setSnapshot(current => failure instanceof ApiError && failure.status === 403
        ? { items: null, lastRead: null, error }
        : { ...current, error });
      return true;
    });
    reader.current = polling;
    const visibility = () => polling.setVisible(document.visibilityState === 'visible');
    document.addEventListener('visibilitychange', visibility);
    visibility();
    return () => {
      document.removeEventListener('visibilitychange', visibility);
      polling.setVisible(false);
      reader.current = null;
    };
  }, [session, eventId, onExpiry]);

  return { ...snapshot, refresh, invalidate, onAccessLost };
}

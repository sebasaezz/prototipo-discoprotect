import type { Db } from './db.js';
import { normalizeText } from './visual-location.js';

type LocationState = 'PROPOSED' | 'CONFIRMED' | 'INDICATED' | 'REJECTED' | 'DISMISSED' | 'MOVED';
type Location = {
  zoneId: string; zoneName: string; evidenceSeq: number; evidence: string; feature: string | null; venueRevision: number;
};
type LocationRow = Location & { seq: number; state: LocationState; proposalSeq: number | null };
type Target = { incidentId: string; eventId: string };

const selection = `SELECT seq, state, proposal_seq AS proposalSeq, zone_id AS zoneId, zone_name AS zoneName,
  evidence_seq AS evidenceSeq, evidence, feature, venue_revision AS venueRevision
  FROM incident_chat_locations WHERE incident_id = ?`;

function lastLocation(db: Db, id: string) {
  return db.prepare(selection + ' ORDER BY seq DESC LIMIT 1').get(id) as LocationRow | undefined;
}

export function attendeeChatLocation(db: Db, id: string) {
  const row = db.prepare(selection + " AND state IN ('CONFIRMED','INDICATED','MOVED') ORDER BY seq DESC LIMIT 1")
    .get(id) as LocationRow | undefined;
  if (!row || row.state === 'MOVED') return null;
  return {
    zoneId: row.zoneId, zoneName: row.zoneName, messageSeq: row.seq,
    source: row.state === 'CONFIRMED' ? 'CONFIRMED_AI' as const : 'INDICATED' as const,
    evidence: { messageSeq: row.evidenceSeq, quote: row.evidence, feature: row.feature },
  };
}

export function recordLocation(db: Db, target: Target, seq: number, state: LocationState,
  location: Location, proposalSeq: number | null = null) {
  db.prepare(`INSERT INTO incident_chat_locations (incident_id, seq, event_id, zone_id, zone_name, state, proposal_seq,
    evidence_seq, evidence, feature, venue_revision)
    SELECT id, ?, event_id, ?, ?, ?, ?, ?, ?, ?, ? FROM incidents WHERE id = ? AND event_id = ?`)
    .run(seq, location.zoneId, location.zoneName, state, proposalSeq, location.evidenceSeq,
      location.evidence, location.feature, location.venueRevision, target.incidentId, target.eventId);
}

export function supersededInference(db: Db, incidentId: string, zoneId: string, evidenceSeq: number) {
  return Boolean(db.prepare(`SELECT 1 FROM incident_chat_locations WHERE incident_id = ? AND seq > ?
    AND (state = 'MOVED' OR (state = 'REJECTED' AND zone_id = ?)
      OR (state IN ('CONFIRMED', 'INDICATED') AND zone_id <> ?)) LIMIT 1`).get(incidentId, evidenceSeq, zoneId, zoneId));
}

export function canAskLocation(db: Db, incidentId: string, inputSeq: number, zoneId: string) {
  const current = db.prepare('SELECT last_attendee_seq AS seq FROM incident_conversations WHERE incident_id = ?').get(incidentId);
  return current?.seq === inputSeq && attendeeChatLocation(db, incidentId)?.zoneId !== zoneId;
}

const acknowledge = 'Entendido. Registré tu ubicación indicada. El personal deberá verificarla en terreno.';
export const askForClue = '¿Qué otro objeto, letrero o acceso ves cerca?';
const yes = /^(?:si(?:,? (?:lo confirmo|estoy (?:ahi|alli)))?|correcto|exactamente|lo confirmo)$/u;
const no = /^no(?:,? (?:no estoy (?:ahi|alli)|ese no es el lugar))?$/u;
const moved = /^(?:ya no estoy (?:ahi|alli)|me movi|me cambie de lugar)(?:$|[ ,])/u;
export const canonicalPlaceName = (name: string) => normalizeText(name).replace(/\s+/gu, ' ').replace(/^(?:el|la|los|las) /u, '');

export function handleLocationAnswer(db: Db, target: Target, message: { seq: number; text: string }) {
  const { incidentId, eventId } = target;
  const messageText = normalizeText(message.text).replace(/^[¡!¿?]+|[.!?¡¿]+$/gu, '').replace(/\s+/gu, ' ').trim();
  const latest = lastLocation(db, incidentId);
  const pending = latest?.state === 'PROPOSED' ? latest : null;
  const revision = db.prepare('SELECT config_version AS version FROM events WHERE id = ?').get(eventId)?.version;
  if (typeof revision !== 'number') return null;

  const direct = /^(?:si,? )?(?:(?:ahora )?(?:estoy|estamos)|me encuentro) en (.+)$/u.exec(messageText)?.[1];
  if (direct) {
    const matches = db.prepare('SELECT id, name, active FROM zones WHERE event_id = ?').all(eventId).filter(zone =>
      canonicalPlaceName(String(zone.name)) === canonicalPlaceName(direct));
    if (matches.length === 1 && matches[0].active === 1) {
      if (pending?.zoneId === matches[0].id && pending.venueRevision === revision && /^si[ ,]/u.test(messageText)) {
        recordLocation(db, target, message.seq, 'CONFIRMED', pending, pending.seq);
        return acknowledge;
      }
      const reported = attendeeChatLocation(db, incidentId);
      if (!pending && reported?.source === 'CONFIRMED_AI' && reported.zoneId === matches[0].id &&
          reported.zoneName === matches[0].name) return acknowledge;
      recordLocation(db, target, message.seq, 'INDICATED', { zoneId: String(matches[0].id), zoneName: String(matches[0].name),
        evidenceSeq: message.seq, evidence: message.text, feature: null, venueRevision: revision }, pending?.seq);
      return acknowledge;
    }
  }

  if (moved.test(messageText)) {
    const reported = attendeeChatLocation(db, incidentId);
    const previous = pending ?? (reported && db.prepare(selection + ' AND seq = ?')
      .get(incidentId, reported.messageSeq) as LocationRow | undefined);
    if (previous) {
      recordLocation(db, target, message.seq, 'MOVED', previous, pending?.seq);
      return askForClue;
    }
  }
  if (!pending) return null;
  const active = db.prepare('SELECT 1 FROM zones WHERE event_id = ? AND id = ? AND active = 1').get(eventId, pending.zoneId);
  if (yes.test(messageText) && active && pending.venueRevision === revision) {
    recordLocation(db, target, message.seq, 'CONFIRMED', pending, pending.seq);
    return acknowledge;
  }
  if (no.test(messageText) || yes.test(messageText)) {
    recordLocation(db, target, message.seq, 'REJECTED', pending, pending.seq);
    return askForClue;
  }
  recordLocation(db, target, message.seq, 'DISMISSED', pending, pending.seq);
  return null;
}

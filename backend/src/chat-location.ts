import type { Db } from './db.js';
import { isCurrentVisualReport, locationReport, normalizeText, visualScore } from './visual-location.js';
import type { Analysis } from './chat-analysis.js';
import { messageText, venueText } from './chat-text.js';
import { maxZoneDescription, venueZones } from './venue.js';

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

export function catalogLocation(db: Db, incidentId: string, through = Number.MAX_SAFE_INTEGER) {
  const row = db.prepare(`SELECT input_seq AS seq, inferred_zone_name AS zoneName, result_json AS json, finished_at AS finishedAt
    FROM incident_chat_ai_runs WHERE incident_id = ? AND input_seq <= ? AND status = 'COMPLETE'
      AND (json_type(result_json, '$.location') = 'object' OR inferred_zone_name IS NOT NULL)
    ORDER BY coalesce(json_extract(result_json, '$.location.seq'), input_seq) DESC,
      (inferred_zone_name IS NOT NULL) DESC, input_seq DESC LIMIT 1`).get(incidentId, through) as
    { seq: number; zoneName: string | null; json: string; finishedAt: number } | undefined;
  const local = db.prepare(`SELECT l.input_seq AS seq, l.zone_id AS zoneId, l.zone_name AS zoneName,
    l.quote, l.feature, m.created_at AS observedAt
    FROM incident_local_locations l JOIN incidents i ON i.id = l.incident_id
    JOIN events e ON e.id = i.event_id AND e.config_version = l.venue_version
    JOIN incident_messages m ON m.incident_id = l.incident_id AND m.seq = l.input_seq
    WHERE l.incident_id = ? AND l.input_seq <= ? ORDER BY l.input_seq DESC LIMIT 1`).get(incidentId, through) as
    { seq: number; zoneId: string | null; zoneName: string | null; quote: string; feature: string | null; observedAt: string } | undefined;
  const analysis = row ? JSON.parse(row.json) as Analysis : undefined;
  const modelSeq = analysis?.location?.seq ?? row?.seq ?? 0;
  if (local && (local.seq > modelSeq || local.seq === modelSeq && !row?.zoneName)) {
    const inferred = local.zoneId && local.zoneName && !supersededInference(db, incidentId, local.zoneId, local.seq)
      ? { zoneId: local.zoneId, zoneName: local.zoneName, text: null } : null;
    return { location: { seq: local.seq, quote: local.quote }, inferred, observedAt: local.observedAt,
      evidence: inferred ? { messageSeq: local.seq, quote: local.quote, feature: local.feature } : undefined };
  }
  if (!row || !analysis) return undefined;
  const inferred = row.zoneName && analysis.zoneId && !supersededInference(db, incidentId, analysis.zoneId, modelSeq)
    ? { zoneId: analysis.zoneId, zoneName: row.zoneName, text: null } : null;
  return { location: analysis.location, inferred, observedAt: new Date(row.finishedAt).toISOString(),
    evidence: inferred ? analysis.locationEvidence : undefined };
}

const acknowledge = 'Entendido. Registré tu ubicación indicada. El personal deberá verificarla en terreno.';
export const askForClue = '¿Qué otro objeto, letrero o acceso ves cerca?';
const yes = /^(?:si(?:,? (?:lo confirmo|estoy (?:ahi|alli)))?|correcto|exactamente|lo confirmo)$/u;
const no = /^no(?:,? (?:no estoy (?:ahi|alli)|ese no es el lugar))?$/u;
const moved = /^(?:ya no estoy (?:ahi|alli)|me movi|me traslade|me cambie de lugar)(?:$|[ ,])/u;
export const canonicalPlaceName = (name: string) => normalizeText(name).replace(/\s+/gu, ' ').replace(/^(?:el|la|los|las) /u, '');

export function inferLocalLocation(db: Db, target: Target, message: { seq: number; text: string }) {
  const text = messageText(message.text);
  if (!text) return;
  const reports = (text.match(/[^.!?;,]+/gu) ?? []).map(part => part.trim())
    .filter(part => locationReport.test(normalizeText(part)));
  const quote = reports.find(part => isCurrentVisualReport(text, part));
  const correction = !quote ? reports.find(part =>
    /\bno (?:estoy|estamos|veo|vemos|queda|es|en|junto|cerca|al lado)\b/u.test(normalizeText(part)) &&
    !isCurrentVisualReport(text, part)) : undefined;
  const changed = moved.test(normalizeText(text));
  if (!quote && !correction && !changed) return;
  const revision = db.prepare('SELECT config_version AS version FROM events WHERE id = ?').get(target.eventId)?.version;
  if (typeof revision !== 'number') return;
  const matches = venueZones(db, target.eventId).flatMap(zone => {
    if (!quote || !zone.description) return [];
    const feature = venueText(zone.description, maxZoneDescription);
    if (!feature || !venueText(zone.name, 160)) return [];
    const span = normalizeText(quote).split(locationReport).at(-1) ?? '';
    const score = visualScore(span, feature);
    return score >= 2 ? [{ zone, feature, score }] : [];
  }).sort((a, b) => b.score - a.score);
  if (!matches.length && !correction && !changed) return;
  const best = matches[0];
  const probable = best && (!matches[1] || best.score > matches[1].score) ? best : undefined;
  db.prepare('INSERT INTO incident_local_locations VALUES (?, ?, ?, ?, ?, ?, ?)').run(target.incidentId, message.seq,
    probable?.zone.id ?? null, probable?.zone.name ?? null,
    (quote ?? correction ?? text).slice(0, 160), probable?.feature ?? null, revision);
}

export function handleLocationAnswer(db: Db, target: Target, message: { seq: number; text: string }) {
  const { incidentId, eventId } = target;
  const messageText = normalizeText(message.text).replace(/^[¡!¿?]+|[.!?¡¿]+$/gu, '').replace(/\s+/gu, ' ').trim();
  const latest = lastLocation(db, incidentId);
  const pending = latest?.state === 'PROPOSED' && latest.seq === message.seq - 1 ? latest : null;
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
    const inferred = !previous ? db.prepare(`SELECT z.id AS zoneId, r.inferred_zone_name AS zoneName, r.input_seq AS evidenceSeq,
      coalesce(json_extract(r.result_json, '$.location.quote'), m.body) AS evidence, r.venue_revision AS venueRevision
      FROM incident_chat_ai_runs r JOIN incident_messages m ON m.incident_id = r.incident_id AND m.seq = r.input_seq
      JOIN zones z ON z.event_id = ? AND z.id = json_extract(r.result_json, '$.zoneId')
      WHERE r.incident_id = ? AND r.inferred_zone_name IS NOT NULL ORDER BY r.input_seq DESC LIMIT 1`)
      .get(eventId, incidentId) as Omit<Location, 'feature'> | undefined : undefined;
    const reference = previous ?? (inferred ? { ...inferred, venueRevision: inferred.venueRevision ?? revision, feature: null } : null);
    if (reference) {
      recordLocation(db, target, message.seq, 'MOVED', reference, pending?.seq);
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

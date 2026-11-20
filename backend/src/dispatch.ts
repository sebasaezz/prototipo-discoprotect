import type { GeoLocation } from './event-map.js';
import { createHash } from 'node:crypto';
import { z } from 'zod';
import { requireMembership, type Session } from './auth.js';
import { attendeeChatLocation, catalogLocation } from './chat-location.js';
import { readIncidentMemory } from './chat-risk.js';
import { operationalSummary } from './chat-response.js';
import { priority, type Analysis, type Priority } from './chat-analysis.js';
import { atomic, type Db } from './db.js';
import { ApiError } from './errors.js';
import { triageView } from './triage.js';
import { dangerAlert, type ReasonCode } from './triage-contract.js';

export type Thresholds = { earlySeconds: number; lateSeconds: number };
export function activatePolicy(db: Db, thresholds: Thresholds) {
  const version = `h7.2-v2-${thresholds.earlySeconds}-${thresholds.lateSeconds}`;
  db.prepare('INSERT OR IGNORE INTO dispatch_priority_policies VALUES (?, ?, ?, ?)')
    .run(version, thresholds.earlySeconds, thresholds.lateSeconds, new Date().toISOString());
  return { ...thresholds, version };
}

export type DispatchPolicy = ReturnType<typeof activatePolicy>;
const assessment = z.enum(['UNCHANGED', 'CONFIRMED', 'CLEARED']);
type Assessment = z.infer<typeof assessment>;
const location = z.strictObject({
  zoneId: z.string().min(1).max(128).nullable(),
  text: z.string().trim().min(1).max(160).nullable(),
}).refine(value => value.zoneId !== null || value.text !== null);
export const dispatchReviewInput = z.strictObject({
  expectedRevision: z.int().nonnegative(), operationId: z.uuid().toLowerCase(),
  humanPriority: priority.nullable(), confirmedLocation: location.nullable(),
  dangerAssessment: assessment, reason: z.string().trim().min(1).max(240),
});
type ReviewCommand = z.infer<typeof dispatchReviewInput>;
type ReviewRow = {
  revision: number; operationId: string; requestHash: string; actorId: string; actorName: string;
  reviewedAt: string; humanPriority: Priority | null; zoneId: string | null; zoneName: string | null;
  confirmedText: string | null; dangerAssessment: Assessment; reason: string;
};

type Source = {
  geoLocationJson?: string | null;
  id: string; eventId: string; status: string; reasonCode: ReasonCode | null; createdAt: string; updatedAt: string;
  zoneId: string | null; zoneName: string | null; locationText: string | null;
};

const reviews = `SELECT r.revision, r.operation_id AS operationId, r.request_hash AS requestHash,
  r.actor_person_id AS actorId, p.name AS actorName, r.reviewed_at AS reviewedAt,
  r.human_priority AS humanPriority, r.confirmed_zone_id AS zoneId, z.name AS zoneName,
  r.confirmed_text AS confirmedText, r.danger_assessment AS dangerAssessment, r.reason
  FROM incident_dispatch_reviews r JOIN people p ON p.id = r.actor_person_id
  LEFT JOIN zones z ON z.event_id = r.event_id AND z.id = r.confirmed_zone_id WHERE r.incident_id = ?`;

function confirmedLocation(row: ReviewRow | undefined) {
  if (!row || row.zoneId === null && row.confirmedText === null) return null;
  return { zoneId: row.zoneId, zoneName: row.zoneName, text: row.confirmedText };
}

function reviewView(row: ReviewRow) {
  return {
    revision: row.revision, operationId: row.operationId, actor: { id: row.actorId, name: row.actorName },
    reviewedAt: row.reviewedAt, humanPriority: row.humanPriority, confirmedLocation: confirmedLocation(row),
    dangerAssessment: row.dangerAssessment, reason: row.reason,
  };
}

function legacyPriority(urgency: 'IMMEDIATE' | 'PRIORITY' | 'UNDETERMINED' | undefined): Priority | null {
  if (!urgency) return null;
  return urgency === 'IMMEDIATE' ? 'P0' : urgency === 'PRIORITY' ? 'P1' : 'PENDING';
}

export function evaluatePriority(
  source: { status: string; reasonCode: ReasonCode | null; elapsedSeconds: number },
  human: Priority | null, ai: Priority | null, evidence: boolean, danger: Assessment, thresholds: Thresholds,
) {
  const explicitDanger = dangerAlert(source.reasonCode) !== null && danger !== 'CLEARED';
  const provisional = evidence && danger !== 'CLEARED';
  let effectivePriority: Priority = human ?? (ai === 'P0' ? 'P1' : ai) ?? 'PENDING';
  const priorityReasons = [human ? 'Revisión del guardia' : provisional ? 'Peligro textual identificado por IA'
    : ai === 'P0' ? 'Sugerencia crítica pendiente de corroboración' : ai && ai !== 'PENDING'
      ? 'Gravedad sugerida por IA' : 'Pendiente de evaluación · Información insuficiente'];
  let prioritySource = human ? 'HUMAN' : provisional ? 'AI_PROVISIONAL' : ai && ai !== 'PENDING' ? 'AI' : 'PENDING';
  if (danger === 'CONFIRMED' || human === 'P0') {
    effectivePriority = 'P0';
    prioritySource = 'HUMAN';
    if (danger === 'CONFIRMED') priorityReasons.unshift('Peligro confirmado por el guardia');
  } else if (explicitDanger) {
    effectivePriority = 'P0';
    prioritySource = 'DECLARED_DANGER';
    priorityReasons.unshift('Peligro inmediato declarado');
  } else if (provisional) {
    effectivePriority = 'P0';
    if (human) priorityReasons.unshift('Peligro textual pendiente de revisión');
    prioritySource = 'AI_PROVISIONAL';
  }
  if (source.status === 'OPEN' && source.elapsedSeconds >= thresholds.earlySeconds) {
    const next = effectivePriority === 'P3' ? source.elapsedSeconds >= thresholds.lateSeconds ? 'P1' : 'P2'
      : effectivePriority === 'P2' || effectivePriority === 'PENDING' ? 'P1' : effectivePriority;
    if (next !== effectivePriority) {
      priorityReasons.unshift(`${source.elapsedSeconds} s sin asignación`);
      prioritySource = 'WAIT';
      effectivePriority = next;
    }
  }
  return { effectivePriority, prioritySource, priorityReasons };
}

export function dispatchView(db: Db, source: Source, triage: ReturnType<typeof triageView>, policy: DispatchPolicy, now: number) {
  const row = db.prepare(`${reviews} ORDER BY r.revision DESC LIMIT 1`).get(source.id) as ReviewRow | undefined;
  const assessmentRow = db.prepare(`SELECT danger_assessment AS value, observed_attendee_seq AS observedSeq, reviewed_at AS reviewedAt
    FROM incident_dispatch_reviews
    WHERE incident_id = ? AND danger_assessment <> 'UNCHANGED' ORDER BY revision DESC LIMIT 1`).get(source.id);
  const memory = readIncidentMemory(db, source.id);
  const localRisk = memory.risks.at(-1);
  const observedAfterReview = localRisk && assessmentRow && (typeof assessmentRow.observedSeq === 'number'
    ? localRisk.messageSeq > assessmentRow.observedSeq
    : String(db.prepare('SELECT created_at FROM incident_messages WHERE incident_id = ? AND seq = ?')
      .get(source.id, localRisk.messageSeq)?.created_at ?? '') > String(assessmentRow.reviewedAt));
  const danger = (assessmentRow?.value === 'CLEARED' && observedAfterReview ? 'UNCHANGED'
    : assessmentRow?.value ?? 'UNCHANGED') as Assessment;
  const results = db.prepare(`SELECT input_seq AS seq, result_json AS json, finished_at AS finishedAt, inferred_zone_name AS zoneName
    FROM incident_chat_ai_runs WHERE incident_id = ? AND status = 'COMPLETE' AND reason IS NOT 'LOCAL_LOCATION'
    ORDER BY input_seq`).all(source.id) as
    { seq: number; json: string; finishedAt: number; zoneName: string | null }[];
  const analyses = results.map(result => ({ ...result, analysis: JSON.parse(result.json) as Analysis }));
  const latest = analyses.at(-1);
  const analysis = latest?.analysis ?? null;
  const critical = analyses.filter(result => result.analysis.severity === 'P0' && result.analysis.danger !== null).at(-1);
  const evidence = critical?.analysis.danger;
  const oldAiPriority = legacyPriority(triage.suggestion?.urgency);
  const initialAiPriority = analyses[0]?.analysis.severity ?? oldAiPriority;
  const latestAiPriority = analysis?.severity ?? oldAiPriority;
  const humanPriority = row ? row.humanPriority : legacyPriority(triage.latestReview?.urgency);
  const elapsedSeconds = Math.max(0, Math.floor((now - Date.parse(source.createdAt)) / 1000));
  const latestRisk = localRisk && (!evidence || localRisk.messageSeq > evidence.evidence.seq) ? localRisk : null;
  const provisional = memory.consciousness?.value === 'ALTERED' && latestAiPriority !== 'P0' ? 'P1' : latestAiPriority;
  const { effectivePriority, prioritySource, priorityReasons } = evaluatePriority(
    { ...source, elapsedSeconds }, humanPriority, provisional,
    Boolean(evidence || localRisk), danger, policy,
  );
  const localUrgent = prioritySource === 'AI' && effectivePriority === 'P1' && memory.consciousness?.value === 'ALTERED' &&
    latestAiPriority !== 'P0' && latestAiPriority !== 'P1';
  const locationRun = catalogLocation(db, source.id);
  const location = locationRun?.location;
  const declared = source.zoneName || source.locationText || location
    ? { zoneId: source.zoneId, zoneName: source.zoneName, text: source.locationText ?? location?.quote ?? null } : null;
  const inferred = locationRun?.inferred ?? null;
  return {
    effectivePriority,
    prioritySource: prioritySource === 'AI_PROVISIONAL' && latestRisk ? 'LOCAL_RISK' : localUrgent ? 'LOCAL_URGENT' : prioritySource,
    priorityReasons: prioritySource === 'AI_PROVISIONAL' && latestRisk
      ? ['Señal crítica reportada en el chat, pendiente de verificación', ...priorityReasons.filter(reason => reason !== 'Peligro textual identificado por IA')]
      : localUrgent ? ['Posible desmayo reportado, pendiente de verificación'] : priorityReasons,
    initialAiPriority, latestAiPriority, humanPriority,
    policyVersion: policy.version, evaluatedAt: new Date(now).toISOString(), elapsedSeconds,
    ...(source.geoLocationJson ? { geoLocation: JSON.parse(source.geoLocationJson) as GeoLocation } : {}),
    revision: row?.revision ?? 0, confirmedLocation: confirmedLocation(row),
    declaredLocation: declared, inferredLocation: inferred, inferredAt: inferred ? locationRun?.observedAt ?? null : null,
    locationEvidence: locationRun?.evidence ?? null,
    attendeeLocation: attendeeChatLocation(db, source.id), dangerAssessment: danger,
    operational: operationalSummary(memory),
    criticalEvidence: latestRisk ? { kind: latestRisk.kind, messageSeq: latestRisk.messageSeq, analysisSeq: null } : critical && evidence ? {
      kind: evidence.kind, messageSeq: evidence.evidence.seq, analysisSeq: critical.seq,
    } : null,
    analysis: analysis && latest ? {
      category: analysis.category, summary: analysis.summary, uncertainty: analysis.uncertainty,
      observedAt: new Date(latest.finishedAt).toISOString(), inputSeq: latest.seq,
      ...(inferred && locationRun?.evidence ? { locationEvidence: locationRun.evidence } : {}),
    } : null,
  };
}

type Dispatch = ReturnType<typeof dispatchView>;
const priorityRank: Priority[] = ['P0', 'P1', 'PENDING', 'P2', 'P3'];
const activeRank = ['OPEN', 'ASSIGNED', 'IN_PROGRESS'];
const compare = (a: string, b: string) => a < b ? -1 : a > b ? 1 : 0;
export function compareDispatch(a: Source & { dispatch: Dispatch }, b: Source & { dispatch: Dispatch }) {
  const aRank = activeRank.indexOf(a.status);
  const bRank = activeRank.indexOf(b.status);
  if (aRank < 0 && bRank < 0) return compare(b.updatedAt, a.updatedAt) || compare(a.id, b.id);
  if (aRank < 0 || bRank < 0) return aRank < 0 ? 1 : -1;
  return priorityRank.indexOf(a.dispatch.effectivePriority) - priorityRank.indexOf(b.dispatch.effectivePriority) ||
    aRank - bRank || compare(a.createdAt, b.createdAt) || compare(a.id, b.id);
}

export function dispatchAudit(db: Db, id: string, eventId: string, staff: boolean) {
  const history = db.prepare(`${reviews} ORDER BY r.revision`).all(id) as ReviewRow[];
  const transitions = db.prepare(`SELECT t.applied_version AS version, t.from_status AS fromStatus, t.to_status AS toStatus,
    t.applied_at AS appliedAt, p.name AS actorName FROM incident_transitions t
    JOIN people p ON p.id = t.actor_person_id WHERE t.incident_id = ? ORDER BY t.applied_version`).all(id);
  return {
    reviews: history.map(reviewView), transitions,
    ...(staff ? { zones: db.prepare('SELECT id, name FROM zones WHERE event_id = ? AND active = 1 ORDER BY name, id').all(eventId) } : {}),
  };
}

export function reviewDispatch<T>(db: Db, session: Session, eventId: string, id: string, input: ReviewCommand, current: () => T) {
  return atomic(db, () => {
    requireMembership(db, session, eventId);
    const incident = db.prepare('SELECT status, assigned_staff_id AS staffId FROM incidents WHERE event_id = ? AND id = ?')
      .get(eventId, id);
    if (!incident) throw new ApiError(404, 'INCIDENT_NOT_FOUND', 'No se encontró la solicitud');
    if (incident.staffId !== session.personId) throw new ApiError(403, 'FORBIDDEN', 'Solo el guardia asignado puede corregir el despacho');
    const { operationId, expectedRevision, humanPriority, confirmedLocation: loc, dangerAssessment, reason } = input;
    const hash = createHash('sha256')
      .update(JSON.stringify([expectedRevision, humanPriority, loc?.zoneId, loc?.text, dangerAssessment, reason])).digest('hex');
    const recorded = db.prepare(`${reviews} AND r.operation_id = ?`).get(id, operationId) as ReviewRow | undefined;
    if (recorded) {
      if (recorded.requestHash !== hash || recorded.actorId !== session.personId) {
        throw new ApiError(409, 'REQUEST_CONFLICT', 'La clave ya se usó con otros datos');
      }
      return { review: reviewView(recorded), dispatch: current(), replayed: true };
    }
    const latest = db.prepare('SELECT MAX(revision) AS revision FROM incident_dispatch_reviews WHERE incident_id = ?').get(id);
    const revision = Number(latest?.revision ?? 0);
    if (expectedRevision !== revision || !['ASSIGNED', 'IN_PROGRESS'].includes(String(incident.status))) {
      throw new ApiError(409, 'DISPATCH_CONFLICT', 'El despacho cambió', { currentRevision: revision, currentStatus: incident.status });
    }
    if (loc?.zoneId && !db.prepare('SELECT 1 FROM zones WHERE event_id = ? AND id = ?').get(eventId, loc.zoneId)) {
      throw new ApiError(400, 'INVALID_INPUT', 'La zona no pertenece al evento');
    }
    const observedSeq = db.prepare('SELECT last_attendee_seq AS seq FROM incident_conversations WHERE incident_id = ?').get(id)?.seq;
    db.prepare(`INSERT INTO incident_dispatch_reviews VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`).run(
      id, eventId, revision + 1, operationId, hash, session.personId, new Date().toISOString(), humanPriority,
      loc?.zoneId ?? null, loc?.text ?? null, dangerAssessment, reason, observedSeq ?? null);
    const saved = db.prepare(`${reviews} AND r.revision = ?`).get(id, revision + 1) as ReviewRow;
    return { review: reviewView(saved), dispatch: current(), replayed: false };
  });
}

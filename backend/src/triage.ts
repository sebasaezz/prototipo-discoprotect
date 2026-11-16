import { createHash } from 'node:crypto';
import { z } from 'zod';
import { requireMembership, type Session } from './auth.js';
import { atomic, type Db } from './db.js';
import { ApiError } from './errors.js';
import { fallback, reviewInput, type ReasonCode, type Suggestion } from './triage-contract.js';

export type FallbackReason = 'LEGACY' | 'INSUFFICIENT_INPUT' | 'NOT_CONFIGURED' | 'UNSAFE_TEXT' |
  'CLOSED' | 'HUMAN_REVIEWED' | 'INTERRUPTED' | 'EXPIRED' | 'DAILY_LIMIT' | 'TIMEOUT' |
  'NETWORK' | 'PROVIDER_ERROR' | 'INVALID_RESPONSE' | 'DANGER_DOWNGRADE';

type State = {
  status: 'PENDING' | 'SUGGESTED' | 'MANUAL';
  triageVersion: number;
  fallbackReason: FallbackReason | null;
  resultJson: string | null;
};

type ReviewRow = {
  reviewVersion: number;
  actorId: string;
  actorName: string;
  operationId: string;
  requestHash: string;
  decision: 'CONFIRM' | 'CORRECT';
  category: Suggestion['category'];
  urgency: Suggestion['urgency'];
  reviewedAt: string;
};

const reviewSelection = `SELECT r.applied_version AS reviewVersion, r.actor_person_id AS actorId, p.name AS actorName,
  r.operation_id AS operationId, r.request_hash AS requestHash, r.decision, r.category, r.urgency, r.reviewed_at AS reviewedAt
  FROM incident_triage_reviews r JOIN people p ON p.id = r.actor_person_id WHERE r.incident_id = ?`;

function reviewView({ actorId, actorName, requestHash, ...review }: ReviewRow) {
  return { ...review, reviewedTriageVersion: review.reviewVersion - 1, actor: { id: actorId, name: actorName } };
}

export function triageView(db: Db, id: string) {
  const state = db.prepare(`SELECT status, version AS triageVersion, fallback_reason AS fallbackReason,
    result_json AS resultJson FROM incident_triage WHERE incident_id = ?`).get(id) as State;
  const latest = db.prepare(`${reviewSelection} ORDER BY r.applied_version DESC LIMIT 1`).get(id) as ReviewRow | undefined;
  return {
    triageVersion: state.triageVersion,
    status: state.status,
    suggestion: state.resultJson === null ? null : JSON.parse(state.resultJson) as Suggestion,
    fallbackReason: state.fallbackReason,
    fallback: state.status === 'MANUAL' ? fallback : null,
    latestReview: latest ? reviewView(latest) : null,
  };
}

export function initializeTriage(db: Db, id: string, now: number, enabled: boolean,
  reason: ReasonCode | null, description: string | null) {
  const fallbackReason = description === null && (reason === null || reason === 'OTHER') ? 'INSUFFICIENT_INPUT'
    : enabled ? null : 'NOT_CONFIGURED';
  db.prepare(`INSERT INTO incident_triage (incident_id, status, fallback_reason, queued_at, finished_at)
    VALUES (?, ?, ?, ?, ?)`).run(id, fallbackReason ? 'MANUAL' : 'PENDING', fallbackReason, now, fallbackReason ? now : null);
}

export function finishManually(db: Db, id: string, reason: FallbackReason, now: number) {
  db.prepare(`UPDATE incident_triage SET status = 'MANUAL', version = version + 1, fallback_reason = ?, finished_at = ?
    WHERE incident_id = ? AND status = 'PENDING'`).run(reason, now, id);
}

function scopedIncident(db: Db, eventId: string, id: string) {
  const incident = db.prepare(`SELECT status, assigned_staff_id AS assignedStaffId, description
    FROM incidents WHERE event_id = ? AND id = ?`).get(eventId, id) as
    { status: string; assignedStaffId: string | null; description: string | null } | undefined;
  if (!incident) throw new ApiError(404, 'INCIDENT_NOT_FOUND', 'No se encontró la solicitud');
  return incident;
}

export function triageDetail(db: Db, session: Session, eventId: string, id: string) {
  return atomic(db, () => {
    requireMembership(db, session, eventId);
    const incident = scopedIncident(db, eventId, id);
    const reviews = db.prepare(`${reviewSelection} ORDER BY r.applied_version`).all(id) as ReviewRow[];
    return {
      triage: triageView(db, id), reviews: reviews.map(reviewView),
      ...(session.role === 'STAFF' ? { description: incident.description } : {}),
    };
  });
}

export function reviewTriage(db: Db, session: Session, eventId: string, id: string, input: z.infer<typeof reviewInput>) {
  return atomic(db, () => {
    requireMembership(db, session, eventId);
    const incident = scopedIncident(db, eventId, id);
    if (incident.assignedStaffId !== session.personId) {
      throw new ApiError(403, 'FORBIDDEN', 'La revisión requiere ser el guardia asignado');
    }
    const { operationId, decision, triageVersion } = input;
    const classification = input.decision === 'CORRECT' ? [input.category, input.urgency] : [];
    const hash = createHash('sha256').update(JSON.stringify([triageVersion, decision, ...classification])).digest('hex');
    const recorded = db.prepare(`${reviewSelection} AND r.operation_id = ?`).get(id, operationId) as ReviewRow | undefined;
    const triage = triageView(db, id);
    if (recorded) {
      if (recorded.actorId !== session.personId || recorded.requestHash !== hash) {
        throw new ApiError(409, 'REQUEST_CONFLICT', 'La clave de operación ya se usó con otros datos');
      }
      return { review: reviewView(recorded), triage, replayed: true };
    }
    const details = { currentTriageVersion: triage.triageVersion, currentStatus: incident.status };
    if (triageVersion !== triage.triageVersion) {
      throw new ApiError(409, 'TRIAGE_VERSION_CONFLICT', 'El triaje cambió', details);
    }
    const result = input.decision === 'CORRECT' ? input : triage.suggestion;
    if (!result || !['ASSIGNED', 'IN_PROGRESS'].includes(incident.status)) {
      throw new ApiError(409, 'INVALID_TRIAGE_REVIEW', 'El incidente no permite esta revisión', details);
    }
    const now = Date.now();
    if (triage.status === 'PENDING') {
      finishManually(db, id, 'HUMAN_REVIEWED', now);
    } else {
      db.prepare('UPDATE incident_triage SET version = version + 1 WHERE incident_id = ?').run(id);
    }
    db.prepare(`INSERT INTO incident_triage_reviews (incident_id, applied_version, actor_person_id, operation_id,
      request_hash, decision, category, urgency, reviewed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`)
      .run(id, triageVersion + 1, session.personId, operationId, hash, decision, result.category, result.urgency,
        new Date(now).toISOString());
    const updated = triageView(db, id);
    return { review: updated.latestReview!, triage: updated, replayed: false };
  });
}

import { createHash, randomUUID } from 'node:crypto';
import { z } from 'zod';
import { requireMembership, type Session } from './auth.js';
import { atomic, type Db } from './db.js';
import { compareDispatch, dispatchView, type DispatchPolicy } from './dispatch.js';
import { conversationView, createConversation, creationReceipt, discardChatWork, messageInput } from './conversations.js';
import { ApiError } from './errors.js';
import { dangerAlert, reasonCode, type ReasonCode } from './triage-contract.js';
import { finishManually, initializeTriage, triageView } from './triage.js';

const createFields = {
  clientRequestId: z.uuid().transform(value => value.toLowerCase()),
  zoneId: z.string().min(1).max(128).nullish().transform(value => value ?? null),
  locationText: z.string().trim().max(160).nullish().transform(value => value || null),
  reasonCode: reasonCode.nullish().transform(value => value ?? null),
};
export const createInput = z.union([
  z.strictObject({ ...createFields, description: z.string().max(280).nullish().transform(value => value?.trim() ? value : null) }),
  z.strictObject({ ...createFields, mode: z.literal('CHAT'), firstMessage: messageInput }),
  z.strictObject({ ...createFields, mode: z.literal('SOS') }),
]);
export const incidentId = z.strictObject({ id: z.uuid() });
const operationFields = {
  version: z.int().positive(),
  operationId: z.uuid().transform(value => value.toLowerCase()),
};
export const operationInput = z.strictObject(operationFields);
export const resolutionInput = z.strictObject({
  ...operationFields,
  resolutionNote: z.string().trim().max(240).nullish().transform(value => value || null),
});

type Status = 'OPEN' | 'ASSIGNED' | 'IN_PROGRESS' | 'RESOLVED' | 'CANCELLED';

type Incident = {
  id: string;
  eventId: string;
  requesterPersonId: string;
  requesterName: string;
  zoneId: string | null;
  zoneName: string | null;
  locationText: string | null;
  status: Status;
  clientRequestId: string;
  createdAt: string;
  updatedAt: string;
  version: number;
  assignedAt: string | null;
  startedAt: string | null;
  resolvedAt: string | null;
  assignedStaffId: string | null;
  assignedStaffName: string | null;
  resolutionNote: string | null;
  reasonCode: ReasonCode | null;
  description: string | null;
  hasConversation: number;
};

const selection = `SELECT i.id, i.event_id AS eventId, i.requester_person_id AS requesterPersonId,
  p.name AS requesterName, i.zone_id AS zoneId, z.name AS zoneName, i.location_text AS locationText,
  i.status, i.client_request_id AS clientRequestId, i.created_at AS createdAt, i.updated_at AS updatedAt,
  i.version, i.assigned_at AS assignedAt, i.started_at AS startedAt, i.resolved_at AS resolvedAt,
  i.assigned_staff_id AS assignedStaffId, guard.name AS assignedStaffName, i.resolution_note AS resolutionNote,
  i.reason_code AS reasonCode, i.description,
  EXISTS(SELECT 1 FROM incident_conversations c WHERE c.incident_id = i.id) AS hasConversation
  FROM incidents i JOIN people p ON p.id = i.requester_person_id
  LEFT JOIN zones z ON z.event_id = i.event_id AND z.id = i.zone_id
  LEFT JOIN people guard ON guard.id = i.assigned_staff_id`;

function ownView({ assignedStaffId, assignedStaffName, resolutionNote, reasonCode, description, hasConversation, ...incident }: Incident) {
  return { ...incident, ...(hasConversation ? { hasConversation: true } : {}) };
}

function eventView(db: Db, { requesterPersonId, clientRequestId, assignedStaffId, assignedStaffName, description, hasConversation, ...incident }: Incident) {
  return {
    ...incident, ...(hasConversation ? { hasConversation: true } : {}),
    assignedStaff: assignedStaffId ? { id: assignedStaffId, name: assignedStaffName } : null,
    dangerAlert: dangerAlert(incident.reasonCode), triage: triageView(db, incident.id),
  };
}

function findIncident(db: Db, id: string, scope: 'event' | 'owner', value: string): Incident {
  const field = scope === 'event' ? 'event_id' : 'requester_person_id';
  const row = db.prepare(`${selection} WHERE i.id = ? AND i.${field} = ?`).get(id, value) as Incident | undefined;
  if (!row) throw new ApiError(404, 'INCIDENT_NOT_FOUND', 'No se encontró la solicitud');
  return row;
}

export function zones(db: Db, eventId: string) {
  return db.prepare('SELECT id, name FROM zones WHERE event_id = ? ORDER BY name, id').all(eventId);
}

export function ownIncidents(db: Db, eventId: string, personId: string) {
  const rows = db.prepare(`${selection} WHERE i.event_id = ? AND i.requester_person_id = ?
    ORDER BY i.created_at DESC, i.id DESC`).all(eventId, personId) as Incident[];
  return rows.map(ownView);
}

export function eventIncidents(db: Db, eventId: string, dispatch?: DispatchPolicy) {
  const rows = db.prepare(`${selection} WHERE i.event_id = ? ORDER BY i.created_at DESC, i.id DESC`)
    .all(eventId) as Incident[];
  if (!dispatch) return rows.map(row => eventView(db, row));
  const now = Date.now();
  return rows.map(row => {
    const item = eventView(db, row);
    return { ...item, dispatch: dispatchView(db, row, item.triage, dispatch, now) };
  }).sort(compareDispatch);
}

export function ownIncident(db: Db, session: Session, id: string) {
  const incident = findIncident(db, id, 'owner', session.personId);
  requireMembership(db, session, incident.eventId);
  return ownView(incident);
}

export function eventIncident(db: Db, eventId: string, id: string) {
  return eventView(db, findIncident(db, id, 'event', eventId));
}

export function incidentDispatch(db: Db, eventId: string, id: string, policy: DispatchPolicy) {
  const row = findIncident(db, id, 'event', eventId);
  return dispatchView(db, row, triageView(db, id), policy, Date.now());
}

export function createIncident(db: Db, session: Session, eventId: string, input: z.output<typeof createInput>,
  triageEnabled: boolean) {
  return atomic(db, () => {
    requireMembership(db, session, eventId);
    const mode = 'mode' in input ? input.mode : undefined;
    const first = 'firstMessage' in input ? input.firstMessage : undefined;
    const description = 'description' in input ? input.description : null;
    const previous = db.prepare(`${selection} WHERE i.requester_person_id = ? AND i.client_request_id = ?`)
      .get(session.personId, input.clientRequestId) as Incident | undefined;
    if (previous) {
      if (previous.eventId !== eventId || previous.zoneId !== input.zoneId || previous.locationText !== input.locationText ||
          previous.reasonCode !== input.reasonCode || previous.description !== description) {
        throw new ApiError(409, 'REQUEST_CONFLICT', 'La clave de solicitud ya se usó con otros datos');
      }
      const incident = ownView(previous);
      const receipt = creationReceipt(db, previous.id, session.personId, mode, first);
      return { created: false, body: receipt ? { incident, ...receipt } : incident };
    }
    if (input.zoneId !== null && !db.prepare('SELECT 1 FROM zones WHERE event_id = ? AND id = ?').get(eventId, input.zoneId)) {
      throw new ApiError(400, 'INVALID_INPUT', 'La zona no pertenece al evento');
    }
    const id = randomUUID();
    const now = new Date().toISOString();
    db.prepare(`INSERT INTO incidents (id, event_id, requester_person_id, zone_id, location_text,
      status, client_request_id, created_at, updated_at, reason_code, description) VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?)`)
      .run(id, eventId, session.personId, input.zoneId, input.locationText, input.clientRequestId, now, now,
        input.reasonCode, description);
    initializeTriage(db, id, Date.parse(now), !mode && triageEnabled, input.reasonCode, description);
    const receipt = mode ? createConversation(db, id, mode, session.personId, first, now) : undefined;
    const incident = ownView(findIncident(db, id, 'owner', session.personId));
    return { created: true, body: receipt ? { incident, ...receipt } : incident };
  });
}

export function cancelIncident(db: Db, session: Session, id: string) {
  return atomic(db, () => {
    const previous = findIncident(db, id, 'owner', session.personId);
    requireMembership(db, session, previous.eventId);
    if (previous.status !== 'OPEN') {
      throw conflict(previous, 'INCIDENT_NOT_OPEN', 'La solicitud ya no está abierta');
    }
    return ownView(applyTransition(db, previous, session, 'CANCELLED', null, null, null));
  });
}

function conflict(incident: Incident, code: string, message: string) {
  return new ApiError(409, code, message, { currentVersion: incident.version, currentStatus: incident.status });
}

const transitions = {
  claim: { from: 'OPEN', to: 'ASSIGNED' },
  start: { from: 'ASSIGNED', to: 'IN_PROGRESS' },
  resolve: { from: 'IN_PROGRESS', to: 'RESOLVED' },
} as const;

export type Action = keyof typeof transitions;
type OperationInput = z.output<typeof operationInput> & { resolutionNote?: string | null };
type RecordedOperation = {
  actorId: string; requestHash: string; fromStatus: Status; toStatus: Status; appliedVersion: number; appliedAt: string;
};

export function operate(db: Db, session: Session, eventId: string, id: string, action: Action, input: OperationInput,
  policy?: DispatchPolicy) {
  return atomic(db, () => {
    requireMembership(db, session, eventId);
    const previous = findIncident(db, id, 'event', eventId);
    const operational = (row: Incident) => {
      const incident = eventView(db, row);
      return policy && row.hasConversation
        ? { ...incident, dispatch: dispatchView(db, row, incident.triage, policy, Date.now()) } : incident;
    };
    const { operationId, version, resolutionNote = null } = input;
    const requestHash = createHash('sha256').update(JSON.stringify([action, version, resolutionNote])).digest('hex');
    const recorded = db.prepare(`SELECT actor_person_id AS actorId, request_hash AS requestHash,
      from_status AS fromStatus, to_status AS toStatus, applied_version AS appliedVersion, applied_at AS appliedAt
      FROM incident_transitions WHERE incident_id = ? AND operation_id = ?`).get(id, operationId) as RecordedOperation | undefined;
    if (recorded) {
      const { actorId, requestHash: recordedHash, ...operation } = recorded;
      if (actorId !== session.personId || recordedHash !== requestHash) {
        throw new ApiError(409, 'REQUEST_CONFLICT', 'La clave de operación ya se usó con otros datos');
      }
      return { operation: { ...operation, operationId, replayed: true }, incident: operational(previous),
        ...(action === 'claim' && previous.hasConversation ? conversationView(db, id) : {}) };
    }
    const { from, to } = transitions[action];
    if (action !== 'claim' && previous.assignedStaffId && previous.assignedStaffId !== session.personId) {
      throw new ApiError(403, 'FORBIDDEN', 'La operación requiere ser el guardia asignado');
    }
    if (previous.version !== version) throw conflict(previous, 'VERSION_CONFLICT', 'El incidente cambió');
    if (previous.status !== from) throw conflict(previous, 'INVALID_TRANSITION', 'El estado no permite esta operación');
    const current = applyTransition(db, previous, session, to, resolutionNote, operationId, requestHash);
    return {
      operation: { operationId, fromStatus: from, toStatus: to, appliedVersion: current.version, appliedAt: current.updatedAt, replayed: false },
      incident: operational(current),
      ...(action === 'claim' && current.hasConversation ? conversationView(db, id) : {}),
    };
  });
}

function applyTransition(db: Db, previous: Incident, session: Session, to: Status,
  resolutionNote: string | null, operationId: string | null, requestHash: string | null) {
  const updatedAt = new Date(Math.max(Date.now(), Date.parse(previous.updatedAt))).toISOString();
  const version = previous.version + 1;
  const result = db.prepare(`UPDATE incidents SET status = ?, updated_at = ?, version = ?,
    assigned_staff_id = ?, assigned_at = ?, started_at = ?, resolved_at = ?, resolution_note = ?
    WHERE event_id = ? AND id = ? AND status = ? AND version = ?`)
    .run(to, updatedAt, version, to === 'ASSIGNED' ? session.personId : previous.assignedStaffId,
      to === 'ASSIGNED' ? updatedAt : previous.assignedAt, to === 'IN_PROGRESS' ? updatedAt : previous.startedAt,
      to === 'RESOLVED' ? updatedAt : previous.resolvedAt, resolutionNote,
      previous.eventId, previous.id, previous.status, previous.version);
  if (result.changes !== 1) throw conflict(findIncident(db, previous.id, 'event', previous.eventId), 'VERSION_CONFLICT', 'El incidente cambió');
  db.prepare(`INSERT INTO incident_transitions (incident_id, applied_version, actor_person_id,
    from_status, to_status, applied_at, operation_id, request_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?)`)
    .run(previous.id, version, session.personId, previous.status, to, updatedAt, operationId, requestHash);
  if (to === 'CANCELLED' || to === 'RESOLVED') finishManually(db, previous.id, 'CLOSED', Date.parse(updatedAt));
  if (previous.hasConversation && (to === 'ASSIGNED' || to === 'CANCELLED' || to === 'RESOLVED')) {
    discardChatWork(db, previous.id, Date.parse(updatedAt));
  }
  return findIncident(db, previous.id, 'event', previous.eventId);
}

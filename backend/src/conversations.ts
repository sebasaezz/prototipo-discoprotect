import { createHash, randomUUID } from 'node:crypto';
import { z } from 'zod';
import { requireMembership, type Session } from './auth.js';
import { atomic, type Db } from './db.js';
import { ApiError } from './errors.js';
import { handleLocationAnswer } from './chat-location.js';

export const messageInput = z.strictObject({
  clientMessageId: z.uuid().toLowerCase(),
  text: z.string().trim().min(1).max(500).refine(value => !/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/u.test(value)),
});
export type MessageInput = z.infer<typeof messageInput>;
export const pageInput = z.strictObject({
  afterSeq: z.string().regex(/^\d+$/).transform(Number).pipe(z.int().nonnegative()).optional().default(0),
  limit: z.string().regex(/^\d+$/).transform(Number).pipe(z.int().min(1).max(100)).optional().default(50),
});

type ConversationRow = {
  incidentId: string; eventId: string; requesterId: string; staffId: string | null;
  staffName: string | null; status: string; version: number; lastSeq: number; lastAttendeeSeq: number;
};
type MessageRow = {
  id: string; seq: number; type: 'ATTENDEE' | 'AI' | 'STAFF'; personId: string | null;
  displayName: string | null; text: string; createdAt: string; clientMessageId: string | null;
  requestHash: string | null;
};

const messages = `SELECT m.id, m.seq, m.author_type AS type, m.author_person_id AS personId,
  p.name AS displayName, m.body AS text, m.created_at AS createdAt, m.client_message_id AS clientMessageId,
  m.request_hash AS requestHash
  FROM incident_messages m LEFT JOIN people p ON p.id = m.author_person_id WHERE m.incident_id = ?`;

function messageView(row: MessageRow, viewer: string) {
  return {
    id: row.id, seq: row.seq, author: { type: row.type, displayName: row.displayName ?? 'Asistente IA' },
    text: row.text, createdAt: row.createdAt,
    ...(row.personId === viewer ? { clientMessageId: row.clientMessageId } : {}),
  };
}

function find(db: Db, id: string) {
  return db.prepare(`SELECT c.incident_id AS incidentId, c.last_seq AS lastSeq, c.last_attendee_seq AS lastAttendeeSeq,
    i.event_id AS eventId, i.requester_person_id AS requesterId, i.assigned_staff_id AS staffId,
    p.name AS staffName, i.status, i.version
    FROM incident_conversations c JOIN incidents i ON i.id = c.incident_id
    LEFT JOIN people p ON p.id = i.assigned_staff_id WHERE c.incident_id = ?`).get(id) as ConversationRow | undefined;
}

function control(row: ConversationRow) {
  if (row.status === 'OPEN') return 'AUTO';
  return row.status === 'ASSIGNED' || row.status === 'IN_PROGRESS' ? 'STAFF' : 'CLOSED';
}

function view(db: Db, row: ConversationRow) {
  const state = control(row);
  const job = db.prepare(`SELECT status, message_seq AS messageSeq FROM incident_chat_ai_runs
    WHERE incident_id = ? AND input_seq = ?`).get(row.incidentId, row.lastAttendeeSeq);
  const aiStatus = state !== 'AUTO' || !job ? 'MANUAL'
    : job?.status === 'QUEUED' || job?.status === 'RUNNING' ? 'PENDING'
    : job.status === 'COMPLETE' && job.messageSeq !== null ? 'READY' : 'UNAVAILABLE';
  return {
    incidentId: row.incidentId, control: state, controlVersion: row.version, lastSeq: row.lastSeq,
    guard: row.staffId ? { id: row.staffId, displayName: row.staffName } : null,
    aiStatus,
  };
}

export function conversationView(db: Db, id: string) {
  const row = find(db, id);
  return row ? { conversation: view(db, row) } : {};
}

export function discardChatWork(db: Db, id: string, now: number) {
  db.prepare(`UPDATE incident_chat_ai_runs SET status = 'DISCARDED', reason = 'CONTROL_CHANGED', finished_at = ?
    WHERE incident_id = ? AND status = 'QUEUED'`).run(now, id);
}

function scope(db: Db, session: Session, id: string, eventId?: string) {
  if (eventId !== undefined) requireMembership(db, session, eventId);
  const row = find(db, id);
  if (!row || (eventId === undefined ? row.requesterId !== session.personId : row.eventId !== eventId)) {
    throw new ApiError(404, 'CONVERSATION_NOT_FOUND', 'No se encontró la conversación');
  }
  if (eventId === undefined) requireMembership(db, session, row.eventId);
  return row;
}

function hashMessage(text: string) {
  return createHash('sha256').update(text).digest('hex');
}

export function append(db: Db, id: string, type: MessageRow['type'], personId: string | null,
  input: { text: string; clientMessageId?: string }, now: string) {
  const { lastSeq } = db.prepare(`UPDATE incident_conversations SET last_seq = last_seq + 1,
    last_attendee_seq = CASE WHEN ? = 'ATTENDEE' THEN last_seq + 1 ELSE last_attendee_seq END
    WHERE incident_id = ? RETURNING last_seq AS lastSeq`).get(type, id) as { lastSeq: number };
  db.prepare(`INSERT INTO incident_messages (id, incident_id, seq, author_type, author_person_id, body, created_at,
    client_message_id, request_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`).run(
    randomUUID(), id, lastSeq, type, personId, input.text, now,
    input.clientMessageId ?? null, input.clientMessageId ? hashMessage(input.text) : null);
  if (type === 'ATTENDEE') {
    const queued = db.prepare(`INSERT INTO incident_chat_ai_runs (incident_id, input_seq, queued_at, status)
      SELECT id, ?, ?, 'QUEUED' FROM incidents WHERE id = ? AND status = 'OPEN'`).run(lastSeq, Date.parse(now), id);
    if (queued.changes) {
      const target = find(db, id)!;
      const localReply = handleLocationAnswer(db, target, { seq: lastSeq, text: input.text });
      if (localReply) {
        const reply = append(db, id, 'AI', null, { text: localReply }, now);
        db.prepare(`UPDATE incident_chat_ai_runs SET status = 'COMPLETE', reason = 'LOCAL_LOCATION', result_json = '{}',
          message_seq = ?, finished_at = ? WHERE incident_id = ? AND input_seq = ?`)
          .run(reply.seq, Date.parse(now), id, lastSeq);
      }
    }
  }
  return db.prepare(`${messages} AND m.seq = ?`).get(id, lastSeq) as MessageRow;
}

export function createConversation(db: Db, id: string, mode: 'CHAT' | 'SOS', personId: string,
  first: MessageInput | undefined, now: string) {
  db.prepare('INSERT INTO incident_conversations (incident_id, creation_mode, created_at) VALUES (?, ?, ?)').run(id, mode, now);
  if (first) append(db, id, 'ATTENDEE', personId, first, now);
  return creationReceipt(db, id, personId, mode, first);
}

export function creationReceipt(db: Db, id: string, personId: string, mode?: 'CHAT' | 'SOS', first?: MessageInput) {
  const record = db.prepare('SELECT creation_mode AS mode FROM incident_conversations WHERE incident_id = ?').get(id);
  const storedMode = record?.mode ?? 'LEGACY';
  const original = storedMode === 'CHAT' ? db.prepare(`${messages} AND m.seq = 1`).get(id) as MessageRow | undefined : undefined;
  if (storedMode !== (mode ?? 'LEGACY') ||
      (mode === 'CHAT' && (!first || !original || original.clientMessageId !== first.clientMessageId || original.text !== first.text))) {
    throw new ApiError(409, 'REQUEST_CONFLICT', 'La clave de solicitud ya se usó con otros datos');
  }
  if (!record) return undefined;
  return { conversation: view(db, find(db, id)!), message: original ? messageView(original, personId) : null };
}

export function readConversation(db: Db, session: Session, id: string, eventId: string | undefined,
  { afterSeq, limit }: z.infer<typeof pageInput>) {
  const row = scope(db, session, id, eventId);
  const page = db.prepare(`${messages} AND m.seq > ? ORDER BY m.seq LIMIT ?`).all(id, afterSeq, limit + 1) as MessageRow[];
  const items = page.slice(0, limit).map(message => messageView(message, session.personId));
  return { conversation: view(db, row), messages: items, nextAfterSeq: items.at(-1)?.seq ?? afterSeq, hasMore: page.length > limit };
}

export function sendMessage(db: Db, session: Session, id: string, eventId: string | undefined, input: MessageInput) {
  return atomic(db, () => {
    const row = scope(db, session, id, eventId);
    if (session.role === 'STAFF' && row.staffId !== session.personId) {
      throw new ApiError(403, 'FORBIDDEN', 'Solo el guardia asignado puede escribir');
    }
    const recorded = db.prepare(`${messages} AND m.client_message_id = ?`).get(id, input.clientMessageId) as MessageRow | undefined;
    if (recorded) {
      if (recorded.personId !== session.personId || recorded.requestHash !== hashMessage(input.text)) {
        throw new ApiError(409, 'REQUEST_CONFLICT', 'La clave de mensaje ya se usó con otros datos');
      }
      return { created: false, message: messageView(recorded, session.personId), conversation: view(db, row) };
    }
    if (control(row) === 'CLOSED') throw new ApiError(409, 'CHAT_CLOSED', 'La conversación está cerrada');
    const total = db.prepare("SELECT count(*) AS n FROM incident_messages WHERE incident_id = ? AND author_type <> 'AI'").get(id);
    if (Number(total?.n) >= 200) throw new ApiError(409, 'CHAT_LIMIT', 'La conversación alcanzó el límite de mensajes');
    const now = Date.now();
    const recent = db.prepare(`SELECT created_at AS time FROM incident_messages
      WHERE incident_id = ? AND author_person_id = ? AND created_at > ? ORDER BY created_at LIMIT 1 OFFSET 19`)
      .get(id, session.personId, new Date(now - 60_000).toISOString());
    if (recent) throw new ApiError(429, 'CHAT_RATE_LIMIT', 'Espera antes de enviar otro mensaje', { retryAfter: 60 });
    const message = append(db, id, session.role === 'STAFF' ? 'STAFF' : 'ATTENDEE', session.personId, input, new Date(now).toISOString());
    return { created: true, message: messageView(message, session.personId), conversation: view(db, find(db, id)!) };
  });
}

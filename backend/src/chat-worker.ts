import { chatInput, chatPromptVersion, recheckLocation, validateAnalysis,
  venuePromptVersion, type ChatProvider } from './chat-analysis.js';
import { attendeeChatLocation, catalogLocation, supersededInference } from './chat-location.js';
import { append } from './conversations.js';
import { atomic, type Db } from './db.js';
import { reserveDailyCall } from './triage-worker.js';
import { readIncidentMemory, recordTurnReply } from './chat-risk.js';
import { conversationalReply } from './chat-response.js';

type Options = {
  model: string; provider: ChatProvider | null; timeoutMs: number; maxQueueAgeMs: number; dailyLimit: number; maxZones: number;
  separateBudget?: boolean;
};
type Job = { id: string; eventId: string; seq: number; version: number; queuedAt: number; messageSeq: number | null };
const maxIncidentCalls = 20;

export function createChatWorker(db: Db, options: Options, report: (event: object) => void = () => {}) {
  let active: Promise<void> | undefined;
  let abort: AbortController | undefined;
  let stopped = false;

  function respond(job: Job, now: number, failed: boolean, candidate?: ReturnType<typeof validateAnalysis>['result'], zoneName?: string | null) {
    if (job.messageSeq !== null) return job.messageSeq;
    const row = db.prepare(`SELECT c.last_attendee_seq AS latest, m.body AS text
      FROM incident_messages m JOIN incident_conversations c ON c.incident_id = m.incident_id
      WHERE m.incident_id = ? AND m.seq = ?`).get(job.id, job.seq);
    const latest = row?.latest === job.seq;
    if (!latest && db.prepare(`SELECT 1 FROM incident_chat_observations
      WHERE incident_id = ? AND input_seq > ? AND reply_seq IS NOT NULL LIMIT 1`).get(job.id, job.seq)) return null;
    const memory = readIncidentMemory(db, job.id, job.seq);
    const known = attendeeChatLocation(db, job.id);
    const catalog = catalogLocation(db, job.id, job.seq);
    const reply = conversationalReply(memory, {
      text: String(row?.text ?? ''), failed, question: latest ? candidate?.question : null,
      acknowledgment: candidate?.acknowledgment, wording: candidate?.reply,
      zoneName: latest && !known ? (candidate?.location?.seq === job.seq ? zoneName : null) ??
        (catalog?.location?.seq === job.seq ? catalog.inferred?.zoneName : null) : null,
      hasLocation: Boolean(known || candidate?.zoneId || catalog?.inferred),
      allowQuestion: latest,
    });
    const message = append(db, job.id, 'AI', null, { text: reply.text }, new Date(now).toISOString());
    recordTurnReply(db, job.id, job.seq, message.seq, reply.question, reply.protocol);
    return message.seq;
  }

  function reserve() {
    return atomic(db, () => {
      const now = Date.now();
      db.prepare(`UPDATE incident_chat_ai_runs AS r SET status = 'DISCARDED', reason = 'STALE', finished_at = ?
        WHERE status = 'QUEUED' AND NOT EXISTS (
          SELECT 1 FROM incidents i WHERE i.id = r.incident_id AND i.status = 'OPEN'
        )`).run(now);
      const job = db.prepare(`SELECT i.id, i.event_id AS eventId, r.input_seq AS seq, i.version,
          r.queued_at AS queuedAt, r.message_seq AS messageSeq
        FROM incident_chat_ai_runs r JOIN incidents i ON i.id = r.incident_id
        WHERE r.status = 'QUEUED' ORDER BY r.queued_at, i.id, r.input_seq LIMIT 1`).get() as Job | undefined;
      if (!job) return undefined;
      const input = options.provider ? chatInput(db, job.id, job.eventId, job.seq, options.maxZones) : null;
      const used = db.prepare(`SELECT count(*) AS n FROM incident_chat_ai_runs
        WHERE incident_id = ? AND reserved_at IS NOT NULL`).get(job.id);
      const reason = now - job.queuedAt >= options.maxQueueAgeMs ? 'EXPIRED'
        : !options.provider ? 'NOT_CONFIGURED' : !input ? 'INPUT_FILTERED' : Number(used?.n) >= maxIncidentCalls
          ? 'INCIDENT_LIMIT' : !reserveDailyCall(db, now, options.dailyLimit, options.separateBudget ? 'chat' : 'triage') ? 'DAILY_LIMIT' : null;
      if (reason || !input) {
        const messageSeq = respond(job, now, true);
        db.prepare(`UPDATE incident_chat_ai_runs SET status = 'MANUAL', reason = ?, finished_at = ?, message_seq = ?
          WHERE incident_id = ? AND input_seq = ?`).run(reason, now, messageSeq, job.id, job.seq);
        return undefined;
      }
      db.prepare(`UPDATE incident_chat_ai_runs SET status = 'RUNNING', reserved_at = ?, model = ?, prompt_version = ?,
        venue_revision = ? WHERE incident_id = ? AND input_seq = ?`).run(now, options.model,
        input.venueContext ? venuePromptVersion : chatPromptVersion, input.venueContext?.revision ?? null, job.id, job.seq);
      return { job, input };
    });
  }

  async function processNext() {
    const reservation = reserve();
    if (!reservation || !options.provider) return;
    const { job, input } = reservation;
    abort = new AbortController();
    const signal = AbortSignal.any([abort.signal, AbortSignal.timeout(options.timeoutMs)]);
    let analysis: ReturnType<typeof validateAnalysis> | undefined;
    let failure: string | null = null;
    try {
      const response = await options.provider(input, signal);
      signal.throwIfAborted();
      try { analysis = validateAnalysis(response, input); } catch { failure = 'INVALID_RESPONSE'; }
    } catch {
      failure = signal.aborted ? 'TIMEOUT' : 'PROVIDER_ERROR';
    } finally {
      abort = undefined;
    }
    const now = Date.now();
    const outcome = atomic(db, () => {
      const current = db.prepare("SELECT 1 FROM incidents WHERE id = ? AND version = ? AND status = 'OPEN'")
        .get(job.id, job.version);
      const reason = stopped ? 'INTERRUPTED' : !current ? 'STALE' : failure;
      const accepted = reason === null && analysis ? recheckLocation(db, job.eventId, analysis.result, input) : undefined;
      if (accepted?.zoneId && supersededInference(db, job.id, accepted.zoneId, accepted.location?.seq ?? job.seq)) {
        accepted.zoneId = null;
        accepted.locationEvidence = undefined;
      }
      const inferredZoneName = input.zones.find(zone => zone.id === accepted?.zoneId)?.name ?? null;
      const outcome = accepted?.reply === null ? 'UNSAFE_REPLY' : reason;
      const messageSeq = current && !stopped ? respond(job, now, outcome !== null, accepted, inferredZoneName) : job.messageSeq;
      // Free-form model text is never published. Persist the actual turn through its message link.
      if (accepted) accepted.reply = null;
      db.prepare(`UPDATE incident_chat_ai_runs SET status = ?, reason = ?, result_json = ?, message_seq = ?, inferred_zone_name = ?,
        finished_at = ?, input_tokens = ?, output_tokens = ? WHERE incident_id = ? AND input_seq = ?`).run(
        accepted ? 'COMPLETE' : reason === 'STALE' ? 'DISCARDED' : 'MANUAL',
        outcome,
        accepted ? JSON.stringify(accepted) : null, messageSeq, inferredZoneName, now,
        analysis?.usage?.input_tokens ?? null, analysis?.usage?.output_tokens ?? null, job.id, job.seq);
      return outcome;
    });
    report({ reason: outcome, model: options.model, inputTokens: analysis?.usage?.input_tokens,
      outputTokens: analysis?.usage?.output_tokens });
  }

  return {
    tick() {
      if (stopped) return Promise.resolve();
      active ??= processNext().finally(() => { active = undefined; });
      return active;
    },
    recover() {
      atomic(db, () => {
        const now = Date.now();
        const rows = db.prepare(`SELECT i.id, i.event_id AS eventId, r.input_seq AS seq, i.version, r.queued_at AS queuedAt,
          r.message_seq AS messageSeq, i.status FROM incident_chat_ai_runs r JOIN incidents i ON i.id = r.incident_id
          WHERE r.status = 'RUNNING' ORDER BY i.id, r.input_seq`).all() as (Job & { status: string })[];
        for (const job of rows) {
          const reply = job.status === 'OPEN' ? respond(job, now, true) : job.messageSeq;
          db.prepare(`UPDATE incident_chat_ai_runs SET status = 'MANUAL', reason = 'INTERRUPTED', finished_at = ?, message_seq = ?
            WHERE incident_id = ? AND input_seq = ?`).run(now, reply, job.id, job.seq);
        }
      });
    },
    async stop() {
      stopped = true;
      abort?.abort();
      await active;
    },
  };
}

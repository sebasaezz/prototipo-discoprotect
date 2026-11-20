import { chatInput, chatPromptVersion, ChatProviderError, ChatValidationError, recheckLocation, responseMetadata,
  validateAnalysis, venuePromptVersion, type Analysis, type ChatProvider } from './chat-analysis.js';
import { attendeeChatLocation, catalogLocation, supersededInference } from './chat-location.js';
import { append } from './conversations.js';
import { atomic, type Db } from './db.js';
import { reserveDailyCall } from './triage-worker.js';
import { readIncidentMemory, recordTurnReply } from './chat-risk.js';
import { conversationalReply } from './chat-response.js';

import { chatDefaults } from './config.js';

type Options = Partial<Pick<typeof chatDefaults, 'contextBytes' | 'recentMessages' | 'maxIncidentCalls'>> & {
  model: string; provider: ChatProvider | null; timeoutMs: number; maxQueueAgeMs: number; dailyLimit: number; maxZones: number;
  separateBudget?: boolean;
  diagnostics?: boolean;
};
type Job = { id: string; eventId: string; seq: number; version: number; queuedAt: number; messageSeq: number | null };

export function createChatWorker(db: Db, options: Options, report: (event: object) => void = () => {}) {
  let active: Promise<void> | undefined;
  let abort: AbortController | undefined;
  let stopped = false;

  function respond(job: Job, now: number, failed: boolean, candidate?: Analysis, zoneName?: string | null) {
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
      acknowledgment: candidate?.acknowledgment, wording: latest ? candidate?.reply : null,
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
      const diagnostic = options.diagnostics ? (details: object) =>
        report({ eventId: job.eventId, incidentId: job.id, turn: job.seq, ...details }) : undefined;
      const input = options.provider ? chatInput(db, job.id, job.eventId, job.seq, { ...chatDefaults, ...options }, details => diagnostic?.({ stage: 'CONTEXT', ...details })) : null;
      const used = db.prepare(`SELECT count(*) AS n FROM incident_chat_ai_runs
        WHERE incident_id = ? AND reserved_at IS NOT NULL`).get(job.id);
      const reason = now - job.queuedAt >= options.maxQueueAgeMs ? 'EXPIRED'
        : !options.provider ? 'NOT_CONFIGURED' : !input ? 'INPUT_FILTERED' : Number(used?.n) >= (options.maxIncidentCalls ?? chatDefaults.maxIncidentCalls)
          ? 'INCIDENT_LIMIT' : !reserveDailyCall(db, now, options.dailyLimit, options.separateBudget ? 'chat' : 'triage') ? 'DAILY_LIMIT' : null;
      if (reason || !input) {
        const messageSeq = respond(job, now, true);
        db.prepare(`UPDATE incident_chat_ai_runs SET status = 'MANUAL', reason = ?, finished_at = ?, message_seq = ?
          WHERE incident_id = ? AND input_seq = ?`).run(reason, now, messageSeq, job.id, job.seq);
        diagnostic?.({ stage: 'SKIPPED', reason, inferredZone: catalogLocation(db, job.id, job.seq)?.inferred?.zoneId });
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
    let metadata: ReturnType<typeof responseMetadata>;
    let validationError: string | undefined;
    let httpStatus: number | undefined;
    const started = performance.now();
    try {
      const response = await options.provider(input, signal);
      signal.throwIfAborted();
      metadata = responseMetadata(response);
      try { analysis = validateAnalysis(response, input); } catch (error) {
        failure = 'INVALID_RESPONSE';
        validationError = error instanceof ChatValidationError ? error.message :
          error instanceof SyntaxError ? 'INVALID_JSON' : 'INVALID_SCHEMA';
      }
    } catch (error) {
      if (error instanceof ChatProviderError) httpStatus = error.status;
      failure = signal.aborted ? 'TIMEOUT' : 'PROVIDER_ERROR';
    } finally {
      abort = undefined;
    }
    const elapsedMs = Math.round(performance.now() - started);
    const now = Date.now();
    const outcome = atomic(db, () => {
      const current = db.prepare("SELECT 1 FROM incidents WHERE id = ? AND version = ? AND status = 'OPEN'")
        .get(job.id, job.version);
      const reason = stopped ? 'INTERRUPTED' : !current ? 'STALE' : failure;
      const rechecked = reason === null && analysis ? recheckLocation(db, job.eventId, analysis.result, input) : undefined;
      const accepted = rechecked?.result;
      let locationReason = reason ?? rechecked?.reason ?? analysis?.locationReason ?? validationError ?? null;
      const evidenceSeq = accepted?.location?.seq ?? job.seq;
      const newerLocalReport = accepted?.zoneId && db.prepare(`SELECT 1 FROM incident_local_locations
        WHERE incident_id = ? AND input_seq > ? AND venue_version = ? LIMIT 1`)
        .get(job.id, evidenceSeq, input.venueContext?.revision ?? null);
      if (accepted?.zoneId && (newerLocalReport || supersededInference(db, job.id, accepted.zoneId, evidenceSeq))) {
        accepted.zoneId = null;
        accepted.locationEvidence = undefined;
        locationReason = 'SUPERSEDED_REPORT';
      }
      const inferredZoneName = input.zones.find(zone => zone.id === accepted?.zoneId)?.name ?? null;
      const outcome = accepted && analysis?.replyViolation ? 'UNSAFE_REPLY' : reason;
      const messageSeq = current && !stopped ? respond(job, now, outcome !== null, accepted, inferredZoneName) : job.messageSeq;
      // The published wording and any server-authored question are available through the message link.
      if (accepted) accepted.reply = null;
      db.prepare(`UPDATE incident_chat_ai_runs SET status = ?, reason = ?, result_json = ?, message_seq = ?, inferred_zone_name = ?,
        finished_at = ?, input_tokens = ?, output_tokens = ? WHERE incident_id = ? AND input_seq = ?`).run(
        accepted ? 'COMPLETE' : reason === 'STALE' ? 'DISCARDED' : 'MANUAL',
        outcome,
        accepted ? JSON.stringify(accepted) : null, messageSeq, inferredZoneName, now,
        metadata?.usage?.input_tokens ?? null, metadata?.usage?.output_tokens ?? null, job.id, job.seq);
      return { reason: outcome, acceptedZone: accepted?.zoneId ?? null, locationReason,
        dispatchZone: options.diagnostics ? catalogLocation(db, job.id)?.inferred?.zoneId ?? null : undefined };
    });
    report({ reason: outcome.reason, model: options.model, inputTokens: metadata?.usage?.input_tokens,
      outputTokens: metadata?.usage?.output_tokens, elapsedMs, ...(options.diagnostics ? {
        stage: 'OUTCOME', eventId: job.eventId, incidentId: job.id, turn: job.seq,
        providerStatus: metadata?.status ?? failure, httpStatus, validationError,
        proposedZone: analysis?.proposedZone ?? null, acceptedZone: outcome.acceptedZone,
        locationReason: outcome.locationReason, dispatchZone: outcome.dispatchZone,
      } : {}) });
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

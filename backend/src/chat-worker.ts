import { chatInput, chatPromptVersion, locationQuestion, recheckLocation, replyWithoutLocation, validateAnalysis,
  venuePromptVersion, type ChatProvider } from './chat-analysis.js';
import { askForClue, attendeeChatLocation, canAskLocation, recordLocation } from './chat-location.js';
import { append } from './conversations.js';
import { atomic, type Db } from './db.js';
import { reserveDailyCall } from './triage-worker.js';

type Options = {
  model: string; provider: ChatProvider | null; timeoutMs: number; maxQueueAgeMs: number; dailyLimit: number; maxZones: number;
};
type Job = { id: string; eventId: string; seq: number; version: number };
const maxIncidentCalls = 20;

export function createChatWorker(db: Db, options: Options, report: (event: object) => void = () => {}) {
  let active: Promise<void> | undefined;
  let abort: AbortController | undefined;
  let stopped = false;

  function reserve() {
    return atomic(db, () => {
      const now = Date.now();
      db.prepare(`UPDATE incident_chat_ai_runs AS r SET status = 'DISCARDED', reason = 'STALE', finished_at = ?
        WHERE status = 'QUEUED' AND NOT EXISTS (
          SELECT 1 FROM incidents i WHERE i.id = r.incident_id AND i.status = 'OPEN'
        )`).run(now);
      db.prepare(`UPDATE incident_chat_ai_runs SET status = 'MANUAL', reason = 'EXPIRED', finished_at = ?
        WHERE status = 'QUEUED' AND queued_at <= ?`).run(now, now - options.maxQueueAgeMs);
      const job = db.prepare(`SELECT i.id, i.event_id AS eventId, r.input_seq AS seq, i.version
        FROM incident_chat_ai_runs r JOIN incidents i ON i.id = r.incident_id
        WHERE r.status = 'QUEUED' ORDER BY r.queued_at, i.id, r.input_seq LIMIT 1`).get() as Job | undefined;
      if (!job) return undefined;
      const input = options.provider ? chatInput(db, job.id, job.eventId, job.seq, options.maxZones) : null;
      const used = db.prepare(`SELECT count(*) AS n FROM incident_chat_ai_runs
        WHERE incident_id = ? AND reserved_at IS NOT NULL`).get(job.id);
      const reason = !options.provider ? 'NOT_CONFIGURED' : !input ? 'INPUT_FILTERED' : Number(used?.n) >= maxIncidentCalls
        ? 'INCIDENT_LIMIT' : !reserveDailyCall(db, now, options.dailyLimit) ? 'DAILY_LIMIT' : null;
      if (reason || !input) {
        db.prepare(`UPDATE incident_chat_ai_runs SET status = 'MANUAL', reason = ?, finished_at = ?
          WHERE incident_id = ? AND input_seq = ?`).run(reason, now, job.id, job.seq);
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
      const inferredZoneName = input.zones.find(zone => zone.id === accepted?.zoneId)?.name ?? null;
      const question = accepted?.locationEvidence && locationQuestion(input, inferredZoneName);
      const proposed = question && accepted?.reply === question && accepted.zoneId && inferredZoneName &&
        accepted.locationEvidence && input.venueContext ? {
          zoneId: accepted.zoneId, zoneName: inferredZoneName, evidenceSeq: accepted.locationEvidence.messageSeq,
          evidence: accepted.locationEvidence.quote, feature: accepted.locationEvidence.feature, venueRevision: input.venueContext.revision,
        } : null;
      const target = { incidentId: job.id, eventId: job.eventId };
      const shouldAsk = proposed && proposed.evidenceSeq === job.seq && canAskLocation(db, job.id, job.seq, proposed.zoneId);
      const oldClue = accepted?.reply === askForClue && accepted.location?.seq !== job.seq && attendeeChatLocation(db, job.id);
      if (accepted && ((proposed && !shouldAsk) || oldClue)) {
        accepted.reply = replyWithoutLocation(input, accepted.facts.length > 0);
      }
      const message = accepted?.reply ? append(db, job.id, 'AI', null,
        { text: accepted.reply }, new Date(now).toISOString()) : null;
      if (message && shouldAsk) recordLocation(db, target, message.seq, 'PROPOSED', proposed);
      const outcome = accepted && !message ? 'UNSAFE_REPLY' : reason;
      db.prepare(`UPDATE incident_chat_ai_runs SET status = ?, reason = ?, result_json = ?, message_seq = ?, inferred_zone_name = ?,
        finished_at = ?, input_tokens = ?, output_tokens = ? WHERE incident_id = ? AND input_seq = ?`).run(
        accepted ? 'COMPLETE' : reason === 'STALE' ? 'DISCARDED' : 'MANUAL',
        outcome,
        accepted ? JSON.stringify(accepted) : null, message?.seq ?? null, inferredZoneName, now,
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
      db.prepare(`UPDATE incident_chat_ai_runs SET status = 'MANUAL', reason = 'INTERRUPTED', finished_at = ?
        WHERE status = 'RUNNING'`).run(Date.now());
    },
    async stop() {
      stopped = true;
      abort?.abort();
      await active;
    },
  };
}

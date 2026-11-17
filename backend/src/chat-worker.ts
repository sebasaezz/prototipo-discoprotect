import { chatInput, chatPromptVersion, validateAnalysis, type ChatProvider } from './chat-analysis.js';
import { append } from './conversations.js';
import { atomic, type Db } from './db.js';
import { reserveDailyCall } from './triage-worker.js';

type Options = {
  model: string; provider: ChatProvider | null; timeoutMs: number; maxQueueAgeMs: number; dailyLimit: number; maxZones: number;
};
type Job = { id: string; eventId: string; seq: number; version: number };

export function createChatWorker(db: Db, options: Options, report: (event: object) => void = () => {}) {
  let active: Promise<void> | undefined;
  let abort: AbortController | undefined;
  let stopped = false;

  function reserve() {
    return atomic(db, () => {
      const now = Date.now();
      db.prepare(`UPDATE incident_chat_ai_runs AS r SET status = 'DISCARDED', reason = 'STALE', finished_at = ?
        WHERE status = 'QUEUED' AND NOT EXISTS (
          SELECT 1 FROM incident_conversations c JOIN incidents i ON i.id = c.incident_id
          WHERE i.id = r.incident_id AND i.status = 'OPEN' AND c.last_attendee_seq = r.input_seq
        )`).run(now);
      db.prepare(`UPDATE incident_chat_ai_runs SET status = 'MANUAL', reason = 'EXPIRED', finished_at = ?
        WHERE status = 'QUEUED' AND queued_at <= ?`).run(now, now - options.maxQueueAgeMs);
      const job = db.prepare(`SELECT i.id, i.event_id AS eventId, r.input_seq AS seq, i.version
        FROM incident_chat_ai_runs r JOIN incidents i ON i.id = r.incident_id
        WHERE r.status = 'QUEUED' ORDER BY r.queued_at, i.id, r.input_seq LIMIT 1`).get() as Job | undefined;
      if (!job) return undefined;
      const input = options.provider ? chatInput(db, job.id, job.eventId, options.maxZones) : null;
      const used = db.prepare(`SELECT count(*) AS n FROM incident_chat_ai_runs
        WHERE incident_id = ? AND reserved_at IS NOT NULL`).get(job.id);
      const reason = !options.provider ? 'NOT_CONFIGURED' : !input ? 'INPUT_FILTERED' : Number(used?.n) >= 3
        ? 'INCIDENT_LIMIT' : !reserveDailyCall(db, now, options.dailyLimit) ? 'DAILY_LIMIT' : null;
      if (reason || !input) {
        db.prepare(`UPDATE incident_chat_ai_runs SET status = 'MANUAL', reason = ?, finished_at = ?
          WHERE incident_id = ? AND input_seq = ?`).run(reason, now, job.id, job.seq);
        return undefined;
      }
      db.prepare(`UPDATE incident_chat_ai_runs SET status = 'RUNNING', reserved_at = ?, model = ?, prompt_version = ?
        WHERE incident_id = ? AND input_seq = ?`).run(now, options.model, chatPromptVersion, job.id, job.seq);
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
      const current = db.prepare(`SELECT 1 FROM incidents i JOIN incident_conversations c ON c.incident_id = i.id
        WHERE i.id = ? AND i.version = ? AND i.status = 'OPEN' AND c.last_attendee_seq = ?`)
        .get(job.id, job.version, job.seq);
      const reason = stopped ? 'INTERRUPTED' : !current ? 'STALE' : failure;
      const accepted = reason === null ? analysis : undefined;
      const message = accepted?.result.reply ? append(db, job.id, 'AI', null,
        { text: accepted.result.reply }, new Date(now).toISOString()) : null;
      db.prepare(`UPDATE incident_chat_ai_runs SET status = ?, reason = ?, result_json = ?, message_seq = ?,
        finished_at = ?, input_tokens = ?, output_tokens = ? WHERE incident_id = ? AND input_seq = ?`).run(
        accepted ? 'COMPLETE' : reason === 'STALE' ? 'DISCARDED' : 'MANUAL', reason,
        accepted ? JSON.stringify(accepted.result) : null, message?.seq ?? null, now,
        analysis?.usage?.input_tokens ?? null, analysis?.usage?.output_tokens ?? null, job.id, job.seq);
      return reason;
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

import { z } from 'zod';
import { atomic, type Db } from './db.js';
import { dangerAlert, suggestionSchema, type ReasonCode } from './triage-contract.js';
import { finishManually, type FallbackReason } from './triage.js';
import { hasPersonalData, promptVersion, ProviderHttpError, safeDescription, type Provider } from './triage-provider.js';

export type WorkerOptions = {
  provider: Provider | null;
  model: string;
  timeoutMs: number;
  maxQueueAgeMs: number;
  dailyLimit: number;
};

type Job = { id: string; reasonCode: ReasonCode | null; description: string | null; queuedAt: number };

const envelope = z.object({
  status: z.literal('completed'),
  model: z.string().regex(/^[\w.-]{1,128}$/).optional(),
  output: z.array(z.object({ type: z.string(), content: z.array(z.unknown()).optional() })),
  usage: z.object({ input_tokens: z.int().nonnegative(), output_tokens: z.int().nonnegative() }).nullish(),
});
const message = z.object({ type: z.literal('output_text'), text: z.string() });

function validate(value: unknown) {
  const { output, usage, model } = envelope.parse(value);
  const messages = output.filter(item => item.type === 'message');
  const pieces = messages[0]?.content ?? [];
  if (messages.length !== 1 || pieces.length !== 1 || output.some(item => !['message', 'reasoning'].includes(item.type))) {
    throw new Error('Invalid response');
  }
  const suggestion = suggestionSchema.parse(JSON.parse(message.parse(pieces[0]).text));
  if (hasPersonalData(suggestion.summary.normalize('NFKC'))) throw new Error('Invalid summary');
  return { suggestion, usage, model };
}

export function createTriageWorker(db: Db, options: WorkerOptions, report: (event: object) => void = () => {}) {
  let timer: ReturnType<typeof setInterval> | undefined;
  let active: Promise<void> | undefined;
  let abort: AbortController | undefined;
  let stopped = false;

  function next() {
    return atomic(db, () => {
      const now = Date.now();
      db.prepare(`UPDATE incident_triage SET status = 'MANUAL', version = version + 1, fallback_reason = 'EXPIRED',
        finished_at = ? WHERE status = 'PENDING' AND attempted_at IS NULL AND queued_at <= ?`)
        .run(now, now - options.maxQueueAgeMs);
      const job = db.prepare(`SELECT i.id, i.reason_code AS reasonCode, i.description, t.queued_at AS queuedAt
        FROM incident_triage t JOIN incidents i ON i.id = t.incident_id
        WHERE t.status = 'PENDING' AND t.attempted_at IS NULL ORDER BY t.queued_at, t.incident_id LIMIT 1`)
        .get() as Job | undefined;
      if (!job) return undefined;
      const description = job.description === null ? undefined : safeDescription(job.description);
      const failure = !options.provider ? 'NOT_CONFIGURED' : description === null ? 'UNSAFE_TEXT' : null;
      if (failure) {
        finishManually(db, job.id, failure, now);
        report({ reason: failure });
        return undefined;
      }
      const reserved = db.prepare(`INSERT INTO triage_daily_usage (day_utc, reserved) VALUES (?, 1)
        ON CONFLICT(day_utc) DO UPDATE SET reserved = reserved + 1 WHERE reserved < ?`)
        .run(new Date(now).toISOString().slice(0, 10), options.dailyLimit);
      if (!reserved.changes) {
        finishManually(db, job.id, 'DAILY_LIMIT', now);
        report({ reason: 'DAILY_LIMIT' });
        return undefined;
      }
      db.prepare(`UPDATE incident_triage SET attempted_at = ?, model = ?, prompt_version = ? WHERE incident_id = ?`)
        .run(now, options.model, promptVersion, job.id);
      return { job, input: { reasonCode: job.reasonCode ?? undefined, description: description ?? undefined }, now };
    });
  }

  async function processNext() {
    const reserved = next();
    if (!reserved || !options.provider) return;
    const { job, input, now } = reserved;
    abort = new AbortController();
    const signal = AbortSignal.any([abort.signal, AbortSignal.timeout(options.timeoutMs)]);
    let result: ReturnType<typeof validate> | undefined;
    let failure: FallbackReason | null = null;
    try {
      const response = await options.provider(input, signal);
      signal.throwIfAborted();
      try {
        result = validate(response);
        if (dangerAlert(job.reasonCode) && result.suggestion.urgency !== 'IMMEDIATE') failure = 'DANGER_DOWNGRADE';
      } catch {
        failure = 'INVALID_RESPONSE';
      }
    } catch (error) {
      failure = signal.aborted ? 'TIMEOUT' : error instanceof ProviderHttpError ? 'PROVIDER_ERROR' : 'NETWORK';
    } finally {
      abort = undefined;
    }
    if (stopped) return;
    const finishedAt = Date.now();
    const model = result?.model ?? options.model;
    const inputTokens = result?.usage?.input_tokens ?? null;
    const outputTokens = result?.usage?.output_tokens ?? null;
    const updated = db.prepare(`UPDATE incident_triage SET status = ?, version = version + 1,
      fallback_reason = ?, result_json = ?, finished_at = ?, model = ?, latency_ms = ?, input_tokens = ?, output_tokens = ?
      WHERE incident_id = ? AND status = 'PENDING' AND attempted_at = ?`).run(
      failure ? 'MANUAL' : 'SUGGESTED', failure, failure ? null : JSON.stringify(result!.suggestion),
      finishedAt, model, finishedAt - now, inputTokens, outputTokens, job.id, now,
    );
    report({ reason: updated.changes ? failure : 'DISCARDED', model, latencyMs: finishedAt - now, inputTokens, outputTokens });
  }

  function tick() {
    if (stopped) return Promise.resolve();
    active ??= processNext().finally(() => { active = undefined; });
    return active;
  }

  return {
    tick,
    recover() {
      db.prepare(`UPDATE incident_triage SET status = 'MANUAL', version = version + 1, fallback_reason = 'INTERRUPTED',
        finished_at = ? WHERE status = 'PENDING' AND attempted_at IS NOT NULL`).run(Date.now());
    },
    start() {
      timer = setInterval(() => { void tick().catch(() => report({ reason: 'WORKER_ERROR' })); }, 1_000);
      timer.unref();
    },
    async stop() {
      stopped = true;
      clearInterval(timer);
      abort?.abort();
      await active;
    },
  };
}

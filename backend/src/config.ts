import { z } from 'zod';

export type Pins = { STAFF?: string; STAFF_2?: string; ORGANIZER?: string };

const pin = z.preprocess(value => value === '' ? undefined : value, z.string().min(4).max(128).optional());
const schema = z.object({
  HOST: z.string().min(1).default('127.0.0.1'),
  PORT: z.coerce.number().int().min(1).max(65535).default(3000),
  DATABASE_PATH: z.string().min(1).default('data/demo.sqlite'),
  DEMO_STAFF_PIN: pin,
  DEMO_STAFF_2_PIN: pin,
  DEMO_ORGANIZER_PIN: pin,
  ALLOW_DEMO_RESET: z.enum(['true', 'false']).default('false'),
  DISPATCH_EARLY_SECONDS: z.coerce.number().int().positive().default(120),
  DISPATCH_LATE_SECONDS: z.coerce.number().int().positive().default(300),
}).refine(value => value.DISPATCH_EARLY_SECONDS < value.DISPATCH_LATE_SECONDS, {
  path: ['DISPATCH_LATE_SECONDS'],
});

const workerSchema = z.object({
  TRIAGE_ENABLED: z.enum(['true', 'false']).default('false'),
  OPENAI_MODEL: z.string().regex(/^[\w.-]{1,128}$/).default('gpt-5.4-mini'),
  TRIAGE_TIMEOUT_MS: z.coerce.number().int().min(1).max(60_000).default(5_000),
  TRIAGE_MAX_QUEUE_AGE_MS: z.coerce.number().int().min(1).max(300_000).default(30_000),
  TRIAGE_DAILY_LIMIT: z.coerce.number().int().min(1).max(10_000).default(100),
});

const chatSchema = z.object({
  OPENAI_CHAT_MODEL: z.string().regex(/^[\w.-]{1,128}$/).default('gpt-6.1-sol'),
  CHAT_AI_TIMEOUT_MS: z.coerce.number().int().min(1).max(60_000).default(15_000),
  CHAT_AI_MAX_ZONES: z.coerce.number().int().min(1).max(32).default(16),
});

export function readChatConfig(env: NodeJS.ProcessEnv) {
  const common = readTriageConfig(env);
  const settings = chatSchema.safeParse(env);
  const values = settings.success ? settings.data : chatSchema.parse({});
  return {
    key: settings.success ? common.key : undefined,
    model: values.OPENAI_CHAT_MODEL,
    timeoutMs: values.CHAT_AI_TIMEOUT_MS,
    maxZones: values.CHAT_AI_MAX_ZONES,
    maxQueueAgeMs: common.maxQueueAgeMs,
    dailyLimit: common.dailyLimit,
  };
}

export function readTriageConfig(env: NodeJS.ProcessEnv) {
  const settings = workerSchema.safeParse(env);
  const values = settings.success ? settings.data : workerSchema.parse({});
  const key = env.OPENAI_API_KEY?.trim();
  return {
    key: settings.success && values.TRIAGE_ENABLED === 'true' && key && /^[\x21-\x7e]+$/.test(key) ? key : undefined,
    model: values.OPENAI_MODEL,
    timeoutMs: values.TRIAGE_TIMEOUT_MS,
    maxQueueAgeMs: values.TRIAGE_MAX_QUEUE_AGE_MS,
    dailyLimit: values.TRIAGE_DAILY_LIMIT,
  };
}

export function readConfig(env: NodeJS.ProcessEnv) {
  const result = schema.safeParse(env);
  if (!result.success) throw new Error(`Configuración inválida en: ${result.error.issues.map(issue => issue.path.join('.')).join(', ')}`);
  const config = result.data;
  const configuredPins = [config.DEMO_STAFF_PIN, config.DEMO_STAFF_2_PIN, config.DEMO_ORGANIZER_PIN].filter(Boolean);
  if (new Set(configuredPins).size !== configuredPins.length) {
    throw new Error('Los PIN de las cuentas privilegiadas deben ser distintos');
  }
  return {
    host: config.HOST,
    port: config.PORT,
    dbPath: config.DATABASE_PATH,
    pins: { STAFF: config.DEMO_STAFF_PIN, STAFF_2: config.DEMO_STAFF_2_PIN, ORGANIZER: config.DEMO_ORGANIZER_PIN },
    allowReset: config.ALLOW_DEMO_RESET === 'true',
    dispatchThresholds: { earlySeconds: config.DISPATCH_EARLY_SECONDS, lateSeconds: config.DISPATCH_LATE_SECONDS },
  };
}

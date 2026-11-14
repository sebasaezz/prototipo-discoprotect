import { z } from 'zod';

export type Pins = { STAFF?: string; ORGANIZER?: string };

const pin = z.preprocess(value => value === '' ? undefined : value, z.string().min(4).max(128).optional());
const schema = z.object({
  HOST: z.string().min(1).default('127.0.0.1'),
  PORT: z.coerce.number().int().min(1).max(65535).default(3000),
  DATABASE_PATH: z.string().min(1).default('data/demo.sqlite'),
  DEMO_STAFF_PIN: pin,
  DEMO_ORGANIZER_PIN: pin,
  ALLOW_DEMO_RESET: z.enum(['true', 'false']).default('false'),
});

export function readConfig(env: NodeJS.ProcessEnv) {
  const result = schema.safeParse(env);
  if (!result.success) throw new Error(`Configuración inválida en: ${result.error.issues.map(issue => issue.path.join('.')).join(', ')}`);
  const config = result.data;
  if (config.DEMO_STAFF_PIN && config.DEMO_STAFF_PIN === config.DEMO_ORGANIZER_PIN) {
    throw new Error('Los PIN de guardia y organizador deben ser distintos');
  }
  return {
    host: config.HOST,
    port: config.PORT,
    dbPath: config.DATABASE_PATH,
    pins: { STAFF: config.DEMO_STAFF_PIN, ORGANIZER: config.DEMO_ORGANIZER_PIN },
    allowReset: config.ALLOW_DEMO_RESET === 'true',
  };
}

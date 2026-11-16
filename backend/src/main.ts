import { createApp } from './app.js';
import { readConfig, readTriageConfig } from './config.js';
import { migrate, openDb } from './db.js';
import { openAi } from './triage-provider.js';
import { createTriageWorker } from './triage-worker.js';

const config = readConfig(process.env);
const db = openDb(config.dbPath);
try {
  migrate(db);
  const { key, ...options } = readTriageConfig(process.env);
  const provider = key ? openAi(options.model, key) : null;
  const app = createApp(db, config.pins, true, provider !== null);
  const worker = createTriageWorker(db, { ...options, provider }, event => app.log.info(event, 'Triaje SOS'));
  worker.recover();
  app.addHook('onClose', async () => {
    try {
      await worker.stop();
    } finally {
      db.close();
    }
  });
  await app.listen({ host: config.host, port: config.port });
  worker.start();
  const close = () => {
    app.close().catch(() => {
      console.error('No se pudo cerrar el servidor correctamente');
      process.exitCode = 1;
    });
  };
  process.once('SIGINT', close);
  process.once('SIGTERM', close);
} catch (error) {
  db.close();
  throw error;
}

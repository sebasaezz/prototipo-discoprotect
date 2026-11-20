import { createApp } from './app.js';
import { readChatConfig, readConfig, readTriageConfig } from './config.js';
import { openAiChat } from './chat-analysis.js';
import { createChatWorker } from './chat-worker.js';
import { migrate, openDb } from './db.js';
import { openAi } from './triage-provider.js';
import { createTriageWorker } from './triage-worker.js';

const config = readConfig(process.env);
const db = openDb(config.dbPath);
try {
  migrate(db);
  const { key, ...options } = readTriageConfig(process.env);
  const provider = key ? openAi(options.model, key) : null;
  const app = createApp(db, config.pins, true, provider !== null, config.dispatchThresholds, config.demoPurgeEnabled, config.firstAid);
  const { key: chatKey, ...chatOptions } = readChatConfig(process.env);
  const chatWorker = createChatWorker(db, { ...chatOptions, provider: chatKey ? openAiChat(chatOptions.model, chatKey, fetch, chatOptions.maxOutputTokens) : null },
    event => app.log.info(event, 'Interpretación del chat'));
  const worker = createTriageWorker(db, { ...options, provider }, event => app.log.info(event, 'Triaje SOS'), chatWorker.tick);
  worker.recover();
  chatWorker.recover();
  app.addHook('onClose', async () => {
    try {
      await Promise.all([worker.stop(), chatWorker.stop()]);
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

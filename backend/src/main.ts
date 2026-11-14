import { createApp } from './app.js';
import { readConfig } from './config.js';
import { migrate, openDb } from './db.js';

const config = readConfig(process.env);
const db = openDb(config.dbPath);
try {
  migrate(db);
  const app = createApp(db, config.pins, true);
  app.addHook('onClose', async () => db.close());
  await app.listen({ host: config.host, port: config.port });
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

import { readConfig } from './config.js';
import { migrate, openDb } from './db.js';
import { resetDemo, seed } from './seed.js';

const config = readConfig(process.env);
const command = process.argv[2];
if (command !== 'migrate' && command !== 'seed' && command !== 'reset') throw new Error('Operación de base de datos desconocida');
if (command === 'reset' && !config.allowReset) throw new Error('La restauración requiere ALLOW_DEMO_RESET=true');

const db = openDb(config.dbPath);
try {
  migrate(db);
  if (command === 'seed') seed(db);
  if (command === 'reset') resetDemo(db);
  console.info(`Operación ${command} completada`);
} finally {
  db.close();
}

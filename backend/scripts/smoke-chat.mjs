// Run once in stage 5. The provider receives one fictional request.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createApp } from '../dist/src/app.js';
import { openAiChat } from '../dist/src/chat-analysis.js';
import { createChatWorker } from '../dist/src/chat-worker.js';
import { readChatConfig } from '../dist/src/config.js';
import { migrate, openDb } from '../dist/src/db.js';
import { seed } from '../dist/src/seed.js';

const { key, ...config } = readChatConfig({ ...process.env, TRIAGE_ENABLED: 'true' });
if (!key) {
  console.error('OPENAI_CHAT_NOT_CONFIGURED');
  process.exit(1);
}
const db = openDb(':memory:');
migrate(db);
seed(db);
const pins = { STAFF: 'fictional-smoke-staff-pin' };
const app = createApp(db, pins);
const providerInfo = { httpStatus: null, providerCode: null };
let calls = 0;
const provider = openAiChat(config.model, key, async (url, options) => {
  assert.equal(++calls, 1);
  const response = await fetch(url, options);
  providerInfo.httpStatus = response.status;
  if (!response.ok) {
    const body = await response.clone().json().catch(() => null);
    const code = body?.error?.code ?? body?.error?.type;
    providerInfo.providerCode = typeof code === 'string' && /^[a-z0-9_]{1,64}$/.test(code) ? code : null;
  }
  return response;
});
const worker = createChatWorker(db, { ...config, dailyLimit: 1, provider });

async function login(account, pin) {
  const response = await app.inject({ method: 'POST', url: '/api/v1/demo/sessions', payload: { account, pin } });
  assert.equal(response.statusCode, 201);
  return { authorization: `Bearer ${response.json().token}` };
}

const queue = '/api/v1/events/demo-festival/incidents';
try {
  const headers = await login('antonia');
  const staff = await login('guardia', pins.STAFF);
  const receipt = await app.inject({ method: 'POST', url: queue, headers, payload: {
    clientRequestId: randomUUID(), mode: 'CHAT',
    firstMessage: { clientMessageId: randomUUID(),
      text: 'Hay una persona que no responde cerca del escenario. Está en el suelo.' },
  } });
  assert.equal(receipt.statusCode, 201);
  assert.equal(calls, 0);
  const { incident } = receipt.json();
  const visible = await app.inject({ url: queue + '?order=dispatch', headers: staff });
  assert.equal(visible.statusCode, 200);
  assert.equal(visible.json().incidents[0].id, incident.id);
  const start = performance.now();
  await worker.tick();
  const run = db.prepare(`SELECT status, reason, input_tokens AS inputTokens, output_tokens AS outputTokens
    FROM incident_chat_ai_runs WHERE incident_id = ?`).get(incident.id);
  const observed = await app.inject({ url: queue + '?order=dispatch', headers: staff });
  assert.equal(observed.statusCode, 200);
  const accepted = observed.json().incidents[0];
  console.log(JSON.stringify({ receipt: receipt.statusCode, calls, model: config.model, ...providerInfo,
    ...run, elapsedMs: Math.round(performance.now() - start), incidentStatus: accepted.status,
    priority: accepted.dispatch.effectivePriority, prioritySource: accepted.dispatch.prioritySource }));
  if (calls !== 1 || run?.status !== 'COMPLETE' || accepted.status !== 'OPEN') process.exitCode = 1;
} catch {
  console.error('OPENAI_CHAT_SMOKE_FAILED');
  process.exitCode = 1;
} finally {
  await worker.stop();
  await app.close();
  db.close();
}

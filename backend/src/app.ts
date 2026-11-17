import Fastify, { LogController, type FastifyRequest } from 'fastify';
import { authenticate, issueSession, requireMembership, requireRole } from './auth.js';
import type { Pins } from './config.js';
import { messageInput, pageInput, readConversation, sendMessage } from './conversations.js';
import type { Db } from './db.js';
import { ApiError, parse } from './errors.js';
import * as incidents from './incidents.js';
import * as records from './records.js';
import { triageDetail, reviewTriage } from './triage.js';
import { reviewInput } from './triage-contract.js';

export function createApp(db: Db, pins: Pins, logging = false, triageEnabled = false) {
  const app = Fastify({ logger: logging, logController: new LogController({ disableRequestLogging: true }), bodyLimit: 32 * 1024 });
  const session = (request: FastifyRequest) => authenticate(db, request.headers.authorization);
  const attendee = (request: FastifyRequest) => {
    const current = session(request);
    requireRole(current, 'ATTENDEE');
    return current.personId;
  };

  app.setErrorHandler((error, request, reply) => {
    if (error instanceof ApiError) {
      if (error.code === 'CHAT_RATE_LIMIT') reply.header('Retry-After', '60');
      return reply.code(error.statusCode).send({ error: { code: error.code, message: error.message, details: error.details } });
    }
    const status = error instanceof Error && 'statusCode' in error ? error.statusCode : undefined;
    if (typeof status === 'number' && status >= 400 && status < 500) {
      return reply.code(status).send({ error: { code: 'INVALID_REQUEST', message: 'La solicitud HTTP no es válida' } });
    }
    const code = error instanceof Error && 'code' in error ? error.code : undefined;
    request.log.error({ code, requestId: request.id }, 'Fallo interno de la API');
    return reply.code(500).send({ error: { code: 'INTERNAL_ERROR', message: 'No se pudo completar la operación' } });
  });
  app.setNotFoundHandler((_request, reply) => reply.code(404).send({
    error: { code: 'NOT_FOUND', message: 'La ruta solicitada no existe' },
  }));
  app.addHook('onRequest', (_request, reply, done) => {
    reply.header('Cache-Control', 'no-store');
    done();
  });

  app.register(async api => {
    api.get('/health', async () => {
      db.prepare('SELECT 1 FROM schema_migrations LIMIT 1').get();
      return { status: 'ok', database: 'ok' };
    });
    api.post('/demo/sessions', async (request, reply) => {
      const { account, pin } = parse(records.issuance, request.body);
      return reply.code(201).send(issueSession(db, pins, account, pin));
    });
    api.get('/me', async request => {
      const current = session(request);
      return { person: records.person(db, current.personId), role: current.role };
    });
    api.get('/me/emergency-card', async request => records.emergencyCard(db, attendee(request)));
    api.put('/me/emergency-card', async request => records.replaceCard(db, attendee(request), parse(records.card, request.body)));
    api.get('/me/contacts', async request => ({ contacts: records.contacts(db, attendee(request)) }));
    api.post('/me/contacts', async (request, reply) => {
      const result = records.writeContact(db, attendee(request), parse(records.contactWrite, request.body));
      return reply.code(201).send(result);
    });
    api.put('/me/contacts/:id', async request => records.writeContact(
      db, attendee(request), parse(records.contactWrite, request.body), parse(records.contactId, request.params).id));
    api.delete('/me/contacts/:id', async request => records.removeContact(
      db, attendee(request), parse(records.contactId, request.params).id, parse(records.version, request.body).version));
    api.get('/me/nfc-credential', async request => records.ownCredential(db, attendee(request)));
    api.get('/events', async request => ({ events: records.events(db, session(request).personId) }));
    api.get('/events/:eventId/zones', async request => {
      const current = session(request);
      const { eventId } = parse(records.eventId, request.params);
      requireMembership(db, current, eventId);
      return { zones: incidents.zones(db, eventId) };
    });
    api.post('/events/:eventId/incidents', async (request, reply) => {
      const current = session(request);
      requireRole(current, 'ATTENDEE');
      const { eventId } = parse(records.eventId, request.params);
      const result = incidents.createIncident(db, current, eventId, parse(incidents.createInput, request.body), triageEnabled);
      return reply.code(result.created ? 201 : 200).send(result.body);
    });
    api.get('/me/incidents', async request => {
      const current = session(request);
      requireRole(current, 'ATTENDEE');
      const { eventId } = parse(records.eventId, request.query);
      requireMembership(db, current, eventId);
      return { incidents: incidents.ownIncidents(db, eventId, current.personId) };
    });
    api.get('/events/:eventId/incidents', async request => {
      const current = session(request);
      if (current.role !== 'ORGANIZER') requireRole(current, 'STAFF');
      const { eventId } = parse(records.eventId, request.params);
      requireMembership(db, current, eventId);
      return { incidents: incidents.eventIncidents(db, eventId) };
    });
    api.post('/me/incidents/:id/cancel', async request => {
      const current = session(request);
      requireRole(current, 'ATTENDEE');
      const { id } = parse(incidents.incidentId, request.params);
      return incidents.cancelIncident(db, current, id);
    });
    api.get('/me/incidents/:id', async request => {
      const current = session(request);
      requireRole(current, 'ATTENDEE');
      const { id } = parse(incidents.incidentId, request.params);
      return incidents.ownIncident(db, current, id);
    });
    const incidentParams = records.eventId.extend(incidents.incidentId.shape);
    const incidentPath = '/events/:eventId/incidents/:id';
    api.get('/me/incidents/:id/conversation', async request => {
      const current = session(request);
      requireRole(current, 'ATTENDEE');
      const { id } = parse(incidents.incidentId, request.params);
      return readConversation(db, current, id, undefined, parse(pageInput, request.query));
    });
    api.get(`${incidentPath}/conversation`, async request => {
      const current = session(request);
      requireRole(current, 'STAFF');
      const { id, eventId } = parse(incidentParams, request.params);
      return readConversation(db, current, id, eventId, parse(pageInput, request.query));
    });
    api.post('/me/incidents/:id/conversation/messages', async (request, reply) => {
      const current = session(request);
      requireRole(current, 'ATTENDEE');
      const { id } = parse(incidents.incidentId, request.params);
      const { created, ...body } = sendMessage(db, current, id, undefined, parse(messageInput, request.body));
      return reply.code(created ? 201 : 200).send(body);
    });
    api.post(`${incidentPath}/conversation/messages`, async (request, reply) => {
      const current = session(request);
      requireRole(current, 'STAFF');
      const { id, eventId } = parse(incidentParams, request.params);
      const { created, ...body } = sendMessage(db, current, id, eventId, parse(messageInput, request.body));
      return reply.code(created ? 201 : 200).send(body);
    });
    api.get(incidentPath, async request => {
      const current = session(request);
      if (current.role !== 'ORGANIZER') requireRole(current, 'STAFF');
      const { eventId, id } = parse(incidentParams, request.params);
      requireMembership(db, current, eventId);
      return incidents.eventIncident(db, eventId, id);
    });
    api.get(`${incidentPath}/triage`, async request => {
      const current = session(request);
      if (current.role !== 'ORGANIZER') requireRole(current, 'STAFF');
      const { eventId, id } = parse(incidentParams, request.params);
      return triageDetail(db, current, eventId, id);
    });
    api.post(`${incidentPath}/triage/reviews`, async request => {
      const current = session(request);
      requireRole(current, 'STAFF');
      const { eventId, id } = parse(incidentParams, request.params);
      return reviewTriage(db, current, eventId, id, parse(reviewInput, request.body));
    });
    for (const action of ['claim', 'start', 'resolve'] as const) {
      api.post(`${incidentPath}/${action}`, async request => {
        const current = session(request);
        requireRole(current, 'STAFF');
        const { eventId, id } = parse(incidentParams, request.params);
        const input = action === 'resolve' ? parse(incidents.resolutionInput, request.body)
          : parse(incidents.operationInput, request.body);
        return incidents.operate(db, current, eventId, id, action, input);
      });
    }
    api.patch('/events/:eventId', async request => {
      const current = session(request);
      requireRole(current, 'ORGANIZER');
      const { eventId } = parse(records.eventId, request.params);
      requireMembership(db, current, eventId);
      return records.updateEvent(db, eventId, current.personId, parse(records.eventEdit, request.body));
    });
    api.post('/nfc/resolve', async request => {
      const current = session(request);
      requireRole(current, 'STAFF');
      const { eventId, token } = parse(records.nfcQuery, request.body);
      requireMembership(db, current, eventId);
      return records.resolveNfc(db, eventId, token);
    });
  }, { prefix: '/api/v1' });

  return app;
}

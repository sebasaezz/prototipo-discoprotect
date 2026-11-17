import Fastify, { LogController, type FastifyRequest } from 'fastify';
import { authenticate, issueSession, requireMembership, requireRole } from './auth.js';
import type { Pins } from './config.js';
import { messageInput, pageInput, readConversation, sendMessage } from './conversations.js';
import type { Db } from './db.js';
import { ApiError, parse } from './errors.js';
import { activatePolicy, dispatchAudit, dispatchReviewInput, reviewDispatch, type Thresholds } from './dispatch.js';
import { z } from 'zod';
import * as incidents from './incidents.js';
import * as records from './records.js';
import { triageDetail, reviewTriage } from './triage.js';
import { reviewInput } from './triage-contract.js';
import { previewPurge, purgeIncidents, purgeInput, purgeTarget } from './incident-purge.js';
import * as venue from './venue.js';

export function createApp(db: Db, pins: Pins, logging = false, triageEnabled = false,
  thresholds: Thresholds = { earlySeconds: 120, lateSeconds: 300 }, demoPurgeEnabled = false) {
  const app = Fastify({ logger: logging, logController: new LogController({ disableRequestLogging: true }), bodyLimit: 32 * 1024 });
  const dispatch = activatePolicy(db, thresholds);
  const queueQuery = z.strictObject({ order: z.literal('dispatch').optional() });
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
    api.get('/events/:eventId/venue', async request => {
      const current = session(request);
      if (current.role !== 'ORGANIZER') requireRole(current, 'STAFF');
      const { eventId } = parse(records.eventId, request.params);
      requireMembership(db, current, eventId);
      return venue.configuration(db, eventId, current.role === 'ORGANIZER');
    });
    api.get('/events/:eventId/zones', async request => {
      const current = session(request);
      const { eventId } = parse(records.eventId, request.params);
      requireMembership(db, current, eventId);
      return { zones: incidents.zones(db, eventId) };
    });
    const zoneParams = records.eventId.extend({ zoneId: z.string().min(1).max(128) });
    const pointParams = records.eventId.extend({ pointId: z.uuid() });
    api.post('/events/:eventId/zones', async (request, reply) => {
      const current = session(request);
      requireRole(current, 'ORGANIZER');
      const { eventId } = parse(records.eventId, request.params);
      return reply.code(201).send(venue.saveZone(db, current, eventId, parse(venue.zoneEdit, request.body)));
    });
    api.patch('/events/:eventId/zones/:zoneId', async request => {
      const current = session(request);
      requireRole(current, 'ORGANIZER');
      const { eventId, zoneId } = parse(zoneParams, request.params);
      return venue.saveZone(db, current, eventId, parse(venue.zoneEdit, request.body), zoneId);
    });
    api.post('/events/:eventId/operational-points', async (request, reply) => {
      const current = session(request);
      requireRole(current, 'ORGANIZER');
      const { eventId } = parse(records.eventId, request.params);
      return reply.code(201).send(venue.savePoint(db, current, eventId, parse(venue.pointEdit, request.body)));
    });
    api.patch('/events/:eventId/operational-points/:pointId', async request => {
      const current = session(request);
      requireRole(current, 'ORGANIZER');
      const { eventId, pointId } = parse(pointParams, request.params);
      return venue.savePoint(db, current, eventId, parse(venue.pointEdit, request.body), pointId);
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
      const { order } = parse(queueQuery, request.query);
      return { incidents: incidents.eventIncidents(db, eventId, order === 'dispatch' ? dispatch : undefined),
        ...(current.role === 'STAFF' ? incidents.guardAttention(db, current, dispatch) : {}) };
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
    const purgePath = '/events/:eventId/incidents/demo-purge';
    api.get(purgePath, async request => {
      const current = session(request);
      requireRole(current, 'ORGANIZER');
      const { eventId } = parse(records.eventId, request.params);
      requireMembership(db, current, eventId);
      return previewPurge(db, eventId, demoPurgeEnabled, parse(purgeTarget, request.query).incidentId);
    });
    api.post(purgePath, async request => {
      const current = session(request);
      requireRole(current, 'ORGANIZER');
      const { eventId } = parse(records.eventId, request.params);
      requireMembership(db, current, eventId);
      return purgeIncidents(db, eventId, current, demoPurgeEnabled, parse(purgeInput, request.body));
    });
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
    api.get(`${incidentPath}/dispatch`, async request => {
      const current = session(request);
      if (current.role !== 'ORGANIZER') requireRole(current, 'STAFF');
      const { id, eventId } = parse(incidentParams, request.params);
      requireMembership(db, current, eventId);
      return { dispatch: incidents.incidentDispatch(db, eventId, id, dispatch),
        ...dispatchAudit(db, id, eventId, current.role === 'STAFF') };
    });
    api.post(`${incidentPath}/dispatch/reviews`, async request => {
      const current = session(request);
      requireRole(current, 'STAFF');
      const { id, eventId } = parse(incidentParams, request.params);
      return reviewDispatch(db, current, eventId, id, parse(dispatchReviewInput, request.body),
        () => incidents.incidentDispatch(db, eventId, id, dispatch));
    });
    api.post(`${incidentPath}/triage/reviews`, async request => {
      const current = session(request);
      requireRole(current, 'STAFF');
      const { eventId, id } = parse(incidentParams, request.params);
      return reviewTriage(db, current, eventId, id, parse(reviewInput, request.body));
    });
    for (const action of ['claim', 'start', 'resolve', 'close', 'withdraw'] as const) {
      api.post(`${incidentPath}/${action}`, async request => {
        const current = session(request);
        requireRole(current, action === 'withdraw' ? 'ORGANIZER' : 'STAFF');
        const { eventId, id } = parse(incidentParams, request.params);
        const schema = action === 'close' || action === 'withdraw' ? incidents.terminationInput
          : action === 'resolve' ? incidents.resolutionInput : incidents.operationInput;
        const input = parse(schema, request.body);
        return incidents.operate(db, current, eventId, id, action, input, dispatch);
      });
    }
    api.patch('/events/:eventId', async request => {
      const current = session(request);
      requireRole(current, 'ORGANIZER');
      const { eventId } = parse(records.eventId, request.params);
      return venue.saveVenue(db, current, eventId, parse(venue.venueEdit, request.body));
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

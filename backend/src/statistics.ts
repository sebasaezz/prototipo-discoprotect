import { z } from 'zod';
import type { Db } from './db.js';
import type { DispatchPolicy } from './dispatch.js';
import { eventDispatchIncidents } from './incidents.js';
import { ApiError } from './errors.js';

const day = z.iso.date();
export const statisticsQuery = z.strictObject({
  from: day.optional(), through: day.optional(),
  status: z.enum(['OPEN', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED', 'CANCELLED', 'CLOSED', 'WITHDRAWN']).optional(),
  priority: z.enum(['P0', 'P1', 'P2', 'P3', 'PENDING']).optional(),
  zoneId: z.string().min(1).max(128).optional(),
});

const timeZone = 'America/Santiago';
const dateParts = new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' });

function localDate(iso: string) {
  const parts = Object.fromEntries(dateParts.formatToParts(new Date(iso)).map(part => [part.type, part.value]));
  return `${parts.year}-${parts.month}-${parts.day}`;
}

function durations(samples: number[]) {
  if (!samples.length) return null;
  samples.sort((a, b) => a - b);
  const middle = Math.floor(samples.length / 2);
  return {
    count: samples.length,
    meanSeconds: samples.reduce((sum, value) => sum + value, 0) / samples.length,
    medianSeconds: samples.length % 2 ? samples[middle] : (samples[middle - 1] + samples[middle]) / 2,
    p90Seconds: samples[Math.ceil(samples.length * 0.9) - 1],
  };
}

export function eventStatistics(db: Db, eventId: string, query: z.infer<typeof statisticsQuery>, policy: DispatchPolicy) {
  if (query.from && query.through && query.from > query.through) {
    throw new ApiError(400, 'INVALID_DATE_RANGE', 'La fecha inicial debe ser anterior o igual a la final');
  }
  const zones = db.prepare('SELECT id, name FROM zones WHERE event_id = ? ORDER BY name, id')
    .all(eventId) as { id: string; name: string }[];
  if (query.zoneId && !zones.some(zone => zone.id === query.zoneId)) {
    throw new ApiError(400, 'INVALID_ZONE', 'La zona no pertenece al evento');
  }
  const status = new Map<string, number>();
  const priority = new Map<string, number>();
  const zone = new Map<string, number>();
  const reason = new Map<string, number>();
  const category = new Map<string, number>();
  const daily = new Map<string, number>();
  const assigned: number[] = [];
  const started: number[] = [];
  const closed: number[] = [];
  let total = 0;
  let active = 0;
  let resolved = 0;
  function count(group: Map<string, number>, key: string) {
    group.set(key, (group.get(key) ?? 0) + 1);
  }
  function measure(samples: number[], createdAt: string, reachedAt: string | null) {
    if (reachedAt !== null) samples.push((Date.parse(reachedAt) - Date.parse(createdAt)) / 1000);
  }
  for (const item of eventDispatchIncidents(db, eventId, policy)) {
    const dispatch = item.dispatch;
    const date = localDate(item.createdAt);
    const location = dispatch.confirmedLocation ?? dispatch.attendeeLocation ??
      (dispatch.declaredLocation?.zoneId ? dispatch.declaredLocation : dispatch.inferredLocation);
    const zoneId = location?.zoneId ?? '';
    if ((query.from && date < query.from) || (query.through && date > query.through) ||
      (query.status && item.status !== query.status) || (query.priority && dispatch.effectivePriority !== query.priority) ||
      (query.zoneId && zoneId !== query.zoneId)) continue;
    total++;
    if (['OPEN', 'ASSIGNED', 'IN_PROGRESS'].includes(item.status)) active++;
    if (item.status === 'RESOLVED') resolved++;
    count(status, item.status);
    count(priority, dispatch.effectivePriority);
    count(zone, zoneId);
    count(reason, item.reasonCode ?? '');
    count(category, item.triage.latestReview?.category ?? dispatch.analysis?.category ?? item.triage.suggestion?.category ?? '');
    count(daily, date);
    measure(assigned, item.createdAt, item.assignedAt);
    measure(started, item.createdAt, item.startedAt);
    measure(closed, item.createdAt, item.resolvedAt ?? (item.status === 'CLOSED' ? item.updatedAt : null));
  }
  return {
    timeZone, zones, total, active, resolved,
    byStatus: Object.fromEntries(status), byPriority: Object.fromEntries(priority),
    byZone: Object.fromEntries(zone), byReason: Object.fromEntries(reason), byCategory: Object.fromEntries(category),
    daily: Object.fromEntries([...daily].sort(([a], [b]) => a.localeCompare(b))),
    durations: { assigned: durations(assigned), started: durations(started), closed: durations(closed) },
  };
}

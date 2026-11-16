import { z } from 'zod';

export const reasonCode = z.enum([
  'UNRESPONSIVE', 'IMMEDIATE_DANGER', 'ILLNESS_OR_INJURY', 'AGGRESSION_OR_HARASSMENT', 'CROWDING', 'OTHER',
]);
export type ReasonCode = z.infer<typeof reasonCode>;
export const category = z.enum(['MEDICAL', 'SECURITY', 'CROWD', 'OTHER', 'UNKNOWN']);
export const urgency = z.enum(['IMMEDIATE', 'PRIORITY', 'UNDETERMINED']);
const uncertainty = z.enum(['MEDIUM', 'HIGH']);
const missing = z.enum(['WHAT_HAPPENED', 'CURRENT_CONDITION', 'ONGOING_DANGER', 'PEOPLE_AFFECTED']);
export const suggestionSchema = z.strictObject({
  category,
  urgency,
  summary: z.string().trim().min(1).max(220),
  missingInformation: z.array(missing).max(3).refine(items => new Set(items).size === items.length),
  uncertainty,
});
export type Suggestion = z.infer<typeof suggestionSchema>;
export const fallback: Suggestion = {
  category: 'UNKNOWN',
  urgency: 'UNDETERMINED',
  summary: 'Verifica lo ocurrido y la situación actual con el personal del evento.',
  missingInformation: ['WHAT_HAPPENED', 'CURRENT_CONDITION', 'ONGOING_DANGER'],
  uncertainty: 'HIGH',
};

export const outputFormat = {
  type: 'json_schema',
  name: 'incident_triage',
  strict: true,
  schema: {
    type: 'object',
    additionalProperties: false,
    required: Object.keys(suggestionSchema.shape),
    properties: {
      category: { type: 'string', enum: category.options },
      urgency: { type: 'string', enum: urgency.options },
      summary: { type: 'string' },
      missingInformation: { type: 'array', items: { type: 'string', enum: missing.options } },
      uncertainty: { type: 'string', enum: uncertainty.options },
    },
  },
};

export const reviewInput = z.discriminatedUnion('decision', [
  z.strictObject({
    triageVersion: z.int().positive(), operationId: z.uuid().toLowerCase(), decision: z.literal('CONFIRM'),
  }),
  z.strictObject({
    triageVersion: z.int().positive(), operationId: z.uuid().toLowerCase(), decision: z.literal('CORRECT'), category, urgency,
  }),
]);

export function dangerAlert(reason: ReasonCode | null) {
  return reason === 'UNRESPONSIVE' || reason === 'IMMEDIATE_DANGER' ? reason : null;
}

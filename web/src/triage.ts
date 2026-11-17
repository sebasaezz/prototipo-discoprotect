export const reasonLabels = {
  UNRESPONSIVE: 'Persona que no responde', IMMEDIATE_DANGER: 'Peligro inmediato',
  ILLNESS_OR_INJURY: 'Malestar o lesión', AGGRESSION_OR_HARASSMENT: 'Agresión o acoso',
  CROWDING: 'Aglomeración', OTHER: 'Otro motivo',
} as const;
export const alertLabels = {
  UNRESPONSIVE: 'Se reportó una persona que no responde', IMMEDIATE_DANGER: 'Se reportó peligro inmediato',
} as const;
export const categoryLabels = {
  MEDICAL: 'Salud o lesión', SECURITY: 'Seguridad', CROWD: 'Aglomeración', OTHER: 'Otro', UNKNOWN: 'Por determinar',
} as const;
export const urgencyLabels = { IMMEDIATE: 'Inmediata', PRIORITY: 'Prioritaria', UNDETERMINED: 'Por determinar' } as const;
export const triageLabels = { PENDING: 'Triaje pendiente', SUGGESTED: 'Sugerencia de IA', MANUAL: 'Triaje manual' } as const;
export const missingLabels = {
  WHAT_HAPPENED: 'Qué ocurrió', CURRENT_CONDITION: 'Situación actual',
  ONGOING_DANGER: 'Si el peligro continúa', PEOPLE_AFFECTED: 'Cuántas personas están afectadas',
} as const;
export const uncertaintyLabels = { MEDIUM: 'Media', HIGH: 'Alta' } as const;

type Classification = { category: keyof typeof categoryLabels; urgency: keyof typeof urgencyLabels };
export type Suggestion = Classification & {
  summary: string;
  missingInformation: (keyof typeof missingLabels)[];
  uncertainty: keyof typeof uncertaintyLabels;
};
export type Review = Classification & {
  reviewVersion: number;
  reviewedTriageVersion: number;
  operationId: string;
  decision: 'CONFIRM' | 'CORRECT';
  reviewedAt: string;
  actor: { id: string; name: string };
};
export type Triage = {
  triageVersion: number;
  status: keyof typeof triageLabels;
  suggestion: Suggestion | null;
  fallbackReason: string | null;
  fallback: Suggestion | null;
  latestReview: Review | null;
};

function validClassification(item: Classification) {
  return !!item && Object.hasOwn(categoryLabels, item.category) && Object.hasOwn(urgencyLabels, item.urgency);
}

function validSuggestion(item: Suggestion | null) {
  return !!item && validClassification(item) && typeof item.summary === 'string' &&
    !!item.summary.trim() && item.summary.length <= 220 && Object.hasOwn(uncertaintyLabels, item.uncertainty) &&
    Array.isArray(item.missingInformation) && item.missingInformation.length <= 3 &&
    item.missingInformation.every(code => Object.hasOwn(missingLabels, code)) &&
    new Set(item.missingInformation).size === item.missingInformation.length;
}

export function validReview(item: Review) {
  return validClassification(item) && Number.isSafeInteger(item.reviewVersion) && item.reviewVersion > 1 &&
    item.reviewedTriageVersion === item.reviewVersion - 1 && typeof item.operationId === 'string' &&
    (item.decision === 'CONFIRM' || item.decision === 'CORRECT') && typeof item.reviewedAt === 'string' &&
    Number.isFinite(Date.parse(item.reviewedAt)) && typeof item.actor?.id === 'string' && typeof item.actor.name === 'string';
}

export function validTriage(item: Triage) {
  if (!item || !Object.hasOwn(triageLabels, item.status) || !Number.isSafeInteger(item.triageVersion) ||
      item.triageVersion < 1 || (item.latestReview !== null &&
      (!validReview(item.latestReview) || item.latestReview.reviewVersion !== item.triageVersion))) return false;
  if (item.status === 'PENDING') return item.latestReview === null && item.suggestion === null &&
    item.fallback === null && item.fallbackReason === null;
  if (item.status === 'MANUAL') return item.suggestion === null && validSuggestion(item.fallback) &&
    typeof item.fallbackReason === 'string';
  return validSuggestion(item.suggestion) && item.fallback === null && item.fallbackReason === null;
}

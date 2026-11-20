export const normalizeText = (value: string) => value.toLowerCase().normalize('NFD').replace(/\p{M}/gu, '').trim();

const ignored = new Set([
  'aca', 'ahi', 'al', 'antes', 'aqui', 'cerca', 'como', 'con', 'contra', 'del', 'desde', 'donde',
  'encima', 'entre', 'esa', 'ese', 'esta', 'este', 'estoy', 'frente', 'hacia', 'hay', 'junto', 'lado',
  'las', 'los', 'para', 'por', 'forma', 'parece', 'veo', 'vemos', 'principal', 'que', 'sector', 'sobre', 'una', 'unos', 'unas', 'zona',
]);
const equivalents = new Map([
  ['estand', 'stand'], ['puesto', 'stand'], ['kiosco', 'stand'], ['quiosco', 'stand'],
  ['cartel', 'letrero'], ['aviso', 'letrero'], ['senal', 'letrero'], ['porton', 'puerta'],
  ['relampago', 'rayo'], ['trueno', 'rayo'], ['violeta', 'morado'], ['morada', 'morado'],
  ['purpura', 'morado'], ['enorme', 'gigante'], ['gigantesco', 'gigante'],
  ['acceso', 'entrada'], ['tarima', 'escenario'],
]);

function cues(text: string) {
  return new Set((normalizeText(text).match(/[\p{L}\p{N}]+/gu) ?? []).flatMap(word => {
    const singular = word.length > 4 ? word.replace(/([aeiou])s$/u, '$1') : word;
    return singular.length >= 3 && !ignored.has(singular) ? [equivalents.get(singular) ?? singular] : [];
  }));
}

function nearSpelling(a: string, b: string) {
  if (Math.min(a.length, b.length) < 5 || Math.abs(a.length - b.length) > 1) return false;
  if (a.length > b.length) [a, b] = [b, a];
  let index = 0;
  while (index < a.length && a[index] === b[index]) index++;
  if (a.length !== b.length) return a.slice(index) === b.slice(index + 1);
  return a.slice(index + 1) === b.slice(index + 1) ||
    (a[index] === b[index + 1] && a[index + 1] === b[index] && a.slice(index + 2) === b.slice(index + 2));
}

// Combine exact, synonymous and near-spelled cues for ranking and comparison.
export function visualScore(report: string, feature: string) {
  const remaining = cues(feature);
  const reported = cues(report);
  const unmatched = [...reported].filter(word => !remaining.delete(word));
  let score = reported.size - unmatched.length;
  for (const word of unmatched) {
    const match = [...remaining].find(candidate => nearSpelling(word, candidate));
    if (match) {
      remaining.delete(match);
      score++;
    }
  }
  return score;
}

export type DescribedZone = { id: string; description: string };

export const locationReport = /\b(?:al (?:lado|lao) de(?:l)?|junto a(?:l)?|cerca de(?:l)?|frente a(?:l)?|detras de(?:l)?|debajo de(?:l)?|est(?:oy|amos) (?:en|por)|veo|vemos|hay|tengo (?:a la vista|al frente))\s+/u;

export function corroboratesFeature(quote: string, description: string, zoneId: string, zones: DescribedZone[]) {
  const score = visualScore(quote, description);
  return !zones.some(zone => zone.id !== zoneId &&
    (normalizeText(zone.description) === normalizeText(description) ||
      visualScore(quote, zone.description) >= Math.max(1, score)));
}

const sentenceBreak = /[.!?;,:]|\b(?:pero|aunque)\b/u;

export function hasLocationCue(text: string, quote: string) {
  const report = normalizeText(text);
  const evidence = normalizeText(quote);
  const index = report.indexOf(evidence);
  const prefix = report.slice(0, index).split(sentenceBreak).at(-1) ?? '';
  return index >= 0 && locationReport.test(prefix + evidence);
}

export function isCurrentVisualReport(text: string, quote: string) {
  const report = normalizeText(text);
  const evidence = normalizeText(quote);
  const index = report.indexOf(evidence);
  if (index < 0) return false;
  const prefix = report.slice(0, index).split(sentenceBreak).at(-1) ?? '';
  const negated = /\b(?:no (?:estoy|estamos|veo|vemos|hay|queda|es|en|junto|cerca|al lado)|ya no (?:se ve|lo veo)|ese no es|lejos de|estaba|me aleje|nos alejamos)\b/u;
  const suffix = report.slice(index + evidence.length).split(sentenceBreak)[0];
  return !`${prefix}${evidence}${suffix}`.split(sentenceBreak).some(clause => negated.test(clause));
}

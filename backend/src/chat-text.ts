import { hasTechnicalSecret, injectedInstruction } from './triage-provider.js';

function cleanText(value: string) {
  return value.normalize('NFKC').replace(/[\p{Cf}\p{Cc}]/gu, character =>
    /\s/u.test(character) ? ' ' : '').replace(/\s+/gu, ' ').trim();
}

export function venueText(value: string | null, max: number) {
  if (!value) return undefined;
  const text = cleanText(value);
  if (!text || hasTechnicalSecret(text) || injectedInstruction.test(text)) return undefined;
  // Keep full fields allowed by the venue schema. Bound older records imported outside that schema.
  return text.length <= max ? text : text.slice(0, max).trimEnd();
}

export function messageText(value: string) {
  const text = cleanText(value);
  return text && !hasTechnicalSecret(text) && !injectedInstruction.test(text) ? text : undefined;
}

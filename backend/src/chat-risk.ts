import { z } from 'zod';
import type { Db } from './db.js';
import { safeDescription } from './triage-provider.js';
import { normalizeText } from './visual-location.js';

export const questionId = z.enum(['BREATHING', 'RESPONSIVENESS', 'SCENE', 'AFFECTED', 'LANDMARK', 'HELP']);
export type Question = z.infer<typeof questionId>;
type Risk = 'UNRESPONSIVE' | 'BREATHING' | 'BLEEDING' | 'SEIZURE' | 'FIRE' | 'VIOLENCE' | 'CRUSH' | 'MULTIPLE_DANGER';
type Observation = {
  quote: string;
  uncertain?: boolean;
  risk?: Risk;
  consciousness?: 'ALTERED' | 'UNRESPONSIVE' | 'RESPONSIVE';
  breathing?: 'ABNORMAL' | 'NORMAL';
  scene?: 'DANGER' | 'SAFE';
  age?: 'INFANT' | 'CHILD' | 'ADULT';
  affectedCount?: number;
  question?: Question;
  answer?: boolean;
  questionSeq?: number;
};
type Recorded<T> = { value: T; seq: number; uncertain: boolean };
export type RiskEvidence = { kind: Risk; messageSeq: number; quote: string; uncertain: boolean; questionSeq?: number };
export type IncidentMemory = {
  consciousness?: Recorded<NonNullable<Observation['consciousness']>>;
  breathing?: Recorded<NonNullable<Observation['breathing']>>;
  scene?: Recorded<NonNullable<Observation['scene']>>;
  age?: Recorded<NonNullable<Observation['age']>>;
  affectedCount?: Recorded<number>;
  risks: RiskEvidence[];
  answers: Partial<Record<Question, boolean>>;
  recentQuestions: Question[];
  contradictory: boolean;
  protocol: string | null;
};

// Match the complete answer. A greeting, conditional or mixed reply carries no binary assertion.
const yes = /^(?:si|correcto|exactamente)$/u;
const no = /^no$/u;
const conditional = /\b(?:si |en caso de|que hago si|hipotetic|podria|simulacro|falsa alarma)/u;
const uncertainty = /\b(?:parece|creo|no se si|quizas|tal vez|posiblemente)\b/u;
const resolvedOrNegated = /\b(?:ya no (?:hay|sangra|convulsiona|se pegan)|(?:ya|ahora) (?:volvio a|esta respirando|responde|respondio)|hubo|habia|fue un|no hay|no esta (?:inconsciente|sangrando|convulsionando)|nunca)\b/u;
const absentBreath = /\b(?:no (?:esta )?respira(?:ndo)?|no puede respirar|(?:solo |apenas )?(?:boquea|jadea)|respira (?:mal|a bocanadas|de forma irregular))\b/u;
const noResponse = /\b(?:no (?:me |nos )?responde|no reacciona|inconsciente|no despierta)\b/u;
const supportingPatterns: ReadonlyArray<readonly [Risk, RegExp]> = [
  ['BLEEDING', /\b(?:hemorragia grave|sangra (?:mucho|sin parar)|sangrado (?:grave|abundante)|mucha sangre que no para)\b/u],
  ['SEIZURE', /\b(?:esta convulsionando|sigue convulsionando|convulsiona ahora|convulsiones en curso)\b/u],
  ['FIRE', /\b(?:hay (?:un )?(?:incendio|fuego|llamas)|se esta quemando|esta en llamas)\b/u],
  ['VIOLENCE', /\b(?:estan (?:pegando|golpeando|disparando)|pelea en curso|agresion en curso|amenaza con (?:un |una )?(?:arma|cuchillo|pistola))\b/u],
  ['CRUSH', /\b(?:nos (?:aplastan|estan aplastando)|estan aplastando|gente aplastada|estampida en curso)\b/u],
  ['MULTIPLE_DANGER', /\b(?:varias personas|varios heridos|mucha gente)\b.{0,40}\b(?:atrapad[oa]s|en peligro inmediato|no responden)\b/u],
];
const quantityWords: Record<string, number> = { un: 1, una: 1, dos: 2, tres: 3, cuatro: 4, cinco: 5, seis: 6, siete: 7, ocho: 8, nueve: 9, diez: 10 };

function asserted(pattern: RegExp, clause: string) {
  const match = pattern.exec(clause);
  return Boolean(match && !/\b(?:no|nunca)\s+(?:(?:esta|estan|sigue|siguen)\s+)?$/u.test(clause.slice(0, match.index)));
}

export function observeAttendee(db: Db, id: string, seq: number, original: string) {
  const safe = safeDescription(original, 500, false);
  const facts: Observation[] = [];
  if (safe) {
    const text = normalizeText(safe).replace(/^[¡!¿?]+|[.!?¡¿]+$/gu, '').trim();
    const previous = db.prepare('SELECT question FROM incident_chat_observations WHERE incident_id = ? AND reply_seq = ?')
      .get(id, seq - 1)?.question as Question | null | undefined;
    const answer = yes.test(text) ? true : no.test(text) ? false : undefined;
    if (previous && answer !== undefined) {
      const observation: Observation = { quote: safe, question: previous, answer, questionSeq: seq - 1 };
      if (previous === 'BREATHING') {
        observation.breathing = answer ? 'NORMAL' : 'ABNORMAL';
        if (!answer) observation.risk = 'BREATHING';
      }
      if (previous === 'RESPONSIVENESS') {
        observation.consciousness = answer ? 'RESPONSIVE' : 'UNRESPONSIVE';
        if (!answer) observation.risk = 'UNRESPONSIVE';
      }
      if (previous === 'SCENE') observation.scene = answer ? 'DANGER' : 'SAFE';
      facts.push(observation);
    } else {
      const clauses = safe.match(/[^.!?;]+[.!?;]?/gu) ?? [];
      for (const raw of clauses) {
        const quote = raw.trim();
        const clause = normalizeText(quote);
        if (conditional.test(clause.replace(/no se si /gu, '')) || /[¿?]/u.test(quote)) continue;
        const uncertain = uncertainty.test(clause);
        const base = { quote: quote.slice(0, 160), uncertain };
        if (/\b(?:estamos a salvo|la zona es segura|no hay peligro cerca)\b/u.test(clause)) facts.push({ ...base, scene: 'SAFE' });
        if (asserted(absentBreath, clause) && !/\b(?:ya respira|ahora respira|volvio a respirar|ya puede respirar)\b/u.test(clause)) {
          facts.push({ ...base, breathing: 'ABNORMAL', risk: 'BREATHING' });
        } else if (/\b(?:respira normalmente|respira bien|volvio a respirar|ya respira)\b/u.test(clause)) {
          facts.push({ ...base, breathing: 'NORMAL' });
        }
        if (asserted(noResponse, clause) && !resolvedOrNegated.test(clause)) {
          facts.push({ ...base, consciousness: 'UNRESPONSIVE', risk: 'UNRESPONSIVE' });
        } else if (/\b(?:ya (?:me |nos )?responde|esta consciente|respondio|desperto)\b/u.test(clause) &&
            !/\b(?:no esta consciente|no respondio|no desperto)\b/u.test(clause)) {
          facts.push({ ...base, consciousness: 'RESPONSIVE' });
        } else if (asserted(/\b(?:desmayad[oa]|se desmayo|perdio el conocimiento)\b/u, clause) && !resolvedOrNegated.test(clause)) {
          facts.push({ ...base, consciousness: 'ALTERED' });
        }
        if (!resolvedOrNegated.test(clause)) {
          for (const [risk, pattern] of supportingPatterns) {
            if (asserted(pattern, clause)) facts.push({ ...base, risk,
              ...(['FIRE', 'VIOLENCE', 'CRUSH', 'MULTIPLE_DANGER'].includes(risk) ? { scene: 'DANGER' as const } : {}) });
          }
        }
        const age = /\b(?:bebe|lactante)\b/u.test(clause) ? 'INFANT'
          : /\b(?:nino|nina|menor de edad)\b/u.test(clause) ? 'CHILD'
            : /\b(?:adult[oa]|mayor de edad)\b/u.test(clause) ? 'ADULT' : undefined;
        if (age && !uncertain && !/\bno es\b/u.test(clause)) facts.push({ ...base, age });
        const quantity = /\b(\d{1,2}|un|una|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez) (?:personas? (?:heridas?|afectadas?|lesionadas?)|herid[oa]s?|afectad[oa]s?)\b/u.exec(clause);
        if (quantity && !resolvedOrNegated.test(clause)) {
          const count = quantityWords[quantity[1]] ?? Number(quantity[1]);
          if (count > 0) facts.push({ ...base, affectedCount: count });
        }
      }
    }
  }
  db.prepare('INSERT INTO incident_chat_observations (incident_id, input_seq, facts_json) VALUES (?, ?, ?)')
    .run(id, seq, JSON.stringify(facts));
  return facts;
}

export function readIncidentMemory(db: Db, incidentId: string, limit = Number.MAX_SAFE_INTEGER): IncidentMemory {
  const rows = db.prepare(`SELECT input_seq AS seq, facts_json AS facts, question, protocol, reply_seq AS replySeq FROM incident_chat_observations
    WHERE incident_id = ? AND input_seq <= ? ORDER BY input_seq`).all(incidentId, limit) as
    { seq: number; facts: string; question: Question | null; protocol: string | null; replySeq: number | null }[];
  const memory: IncidentMemory = { risks: [], answers: {}, recentQuestions: [], contradictory: false, protocol: null };
  for (const row of rows) {
    for (const fact of JSON.parse(row.facts) as Observation[]) {
      for (const field of ['consciousness', 'breathing', 'scene', 'age', 'affectedCount'] as const) {
        const value = fact[field];
        if (value === undefined) continue;
        const previous = memory[field];
        if (previous && previous.value !== value && (field === 'breathing' || field === 'consciousness')) memory.contradictory = true;
        // A later uncertain report can flag danger, but cannot replace a concrete age.
        Object.assign(memory, { [field]: { value, seq: row.seq, uncertain: Boolean(fact.uncertain) } });
      }
      if (fact.question && fact.answer !== undefined) memory.answers[fact.question] = fact.answer;
      if (fact.risk && !memory.risks.some(risk => risk.kind === fact.risk && risk.messageSeq === row.seq)) {
        memory.risks.push({ kind: fact.risk, messageSeq: row.seq, quote: fact.quote,
          uncertain: Boolean(fact.uncertain), ...(fact.questionSeq ? { questionSeq: fact.questionSeq } : {}) });
      }
    }
    if (row.question && row.replySeq !== null && row.replySeq <= limit) {
      memory.recentQuestions = [...memory.recentQuestions, row.question].slice(-3);
    }
    if (row.protocol) memory.protocol = row.protocol;
  }
  return memory;
}

export function recordTurnReply(db: Db, id: string, inputSeq: number, replySeq: number, question: Question | null, protocol: string | null) {
  db.prepare(`UPDATE incident_chat_observations SET reply_seq = ?, question = ?, protocol = ?
    WHERE incident_id = ? AND input_seq = ?`).run(replySeq, question, protocol, id, inputSeq);
}

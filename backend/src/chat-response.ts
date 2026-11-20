import type { IncidentMemory, Question } from './chat-risk.js';
import { normalizeText } from './visual-location.js';

const questions: Record<Question, string> = {
  BREATHING: '¿La persona respira normalmente?',
  RESPONSIVENESS: '¿La persona responde si le hablas?',
  SCENE: '¿Hay algún peligro cerca en este momento?',
  AFFECTED: '¿Hay alguien más afectado?',
  LANDMARK: '¿Ves algún letrero u otra referencia que ayude a encontrarlos?',
  HELP: '¿Hay alguien contigo que pueda ayudarte?',
};
const acknowledgments = {
  LISTEN: 'Te leo.',
  EMPATHY: 'Entiendo que esto puede asustar.',
  UPDATE: 'Entendido, anoté lo que me cuentas.',
} as const;
export type Acknowledgment = keyof typeof acknowledgments;
export type Reply = { text: string; question: Question | null; protocol: string | null };
const neutralWording = /^(?:te (?:leo|escucho)|gracias por (?:avisar(?:me)?|contar(?:lo|melo)|aclarar(?:lo|melo))|entiendo (?:tu preocupacion|que esto puede asustar)|anote (?:tu mensaje|lo que me cuentas)|recibi tu mensaje)[.!]?$/u;

// AHA BLS adult and pediatric 2025, AHA/Red Cross First Aid 2024, Chile SAMU 131.
// The model never supplies protocol wording or enables a protocol.
export const protocolVersion = 'CL-PRIMERA-RESPUESTA-2025-v1';

export function urgentReply(memory: IncidentMemory, seq: number, firstAid: boolean): Reply | null {
  const risks = memory.risks.filter(risk => risk.messageSeq === seq);
  if (!risks.length) return null;
  const has = (kind: string) => risks.some(risk => risk.kind === kind);
  let text: string;
  let question: Question | null = null;
  let protocol = 'URGENT';
  if (memory.scene?.value === 'DANGER' || has('FIRE') || has('VIOLENCE') || has('CRUSH') || has('MULTIPLE_DANGER')) {
    text = 'Ponte a salvo y avisa al personal de emergencia del recinto. Si hay personas heridas, llama al 131 desde un lugar seguro.';
    protocol = 'SCENE_DANGER';
  } else {
    text = 'Puede ser una emergencia vital. Llama al SAMU al 131 o pide a alguien que lo haga ahora.';
    if (firstAid && has('BREATHING')) {
      const pediatric = memory.age?.value === 'CHILD' || memory.age?.value === 'INFANT';
      text += ' Si es seguro acercarte y la persona no responde y no respira normalmente, inicia RCP siguiendo al operador y usa un DEA si está disponible.';
      if (pediatric) text += ' Dile al operador si es un niño o lactante. La RCP pediátrica incluye ventilaciones si puedes darlas.';
      else if (!memory.age) text += ' Indica la edad aproximada al operador para que adapte las instrucciones.';
      protocol = pediatric ? 'BLS_PEDIATRIC' : memory.age ? 'BLS_ADULT' : 'BLS_AGE_UNKNOWN';
    } else if (firstAid && has('BLEEDING')) {
      text += ' Si puedes hacerlo con seguridad, presiona firmemente la herida externa con un paño limpio.';
      protocol = 'SEVERE_BLEEDING';
    } else if (firstAid && has('UNRESPONSIVE') && !memory.breathing && !memory.recentQuestions.includes('BREATHING')) {
      question = 'BREATHING';
      text += ' Si es seguro acercarte, observa su respiración. ' + questions[question];
      protocol = 'CHECK_BREATHING';
    }
  }
  return { text, question, protocol: protocolVersion + '/' + protocol };
}

export function conversationalReply(memory: IncidentMemory, input: {
  text: string; question?: Question | null; acknowledgment?: Acknowledgment; zoneName?: string | null;
  hasLocation?: boolean; failed?: boolean; allowQuestion?: boolean; wording?: string | null;
}): Reply {
  if (memory.scene?.value === 'DANGER') {
    return { text: 'Si todavía hay peligro cerca, quédate en un lugar seguro y avisa al personal del recinto.', question: null, protocol: null };
  }
  const known: Partial<Record<Question, boolean>> = {
    BREATHING: Boolean(memory.breathing), RESPONSIVENESS: Boolean(memory.consciousness && memory.consciousness.value !== 'ALTERED'),
    SCENE: Boolean(memory.scene), LANDMARK: Boolean(input.zoneName || input.hasLocation), AFFECTED: Boolean(memory.affectedCount),
  };
  const available = (question: Question) => !known[question] && memory.answers[question] === undefined &&
    !memory.recentQuestions.includes(question);
  const priority = memory.consciousness?.value === 'ALTERED' || memory.consciousness?.value === 'UNRESPONSIVE'
    ? ['BREATHING', 'RESPONSIVENESS'] as const : [];
  const question = input.allowQuestion === false ? null :
    priority.find(available) ?? (input.question && available(input.question) ? input.question : null);
  const greeting = /^(?:hola+|alo+|buenas)[.!\s]*$/u.test(normalizeText(input.text));
  const zone = input.zoneName && /^[\p{L}\p{M}\p{N}][\p{L}\p{M}\p{N} &()/-]{0,79}$/u.test(input.zoneName) &&
    !/\b(?:llama|evacua|dirigete|enviad[oa]|llegara|tratamiento|toma|administra|inyecta|pastilla|medicamento)\b/u.test(normalizeText(input.zoneName))
    ? `Por lo que describes, la ubicación probable es «${input.zoneName}».` : null;
  const wording = input.wording && neutralWording.test(normalizeText(input.wording))
    ? input.wording.replace(/[.!]?$/u, '.') : null;
  const intro = zone ?? (input.failed ? 'Tu mensaje quedó registrado.' :
    greeting ? 'Hola, te leo.' : wording ?? acknowledgments[input.acknowledgment ?? 'LISTEN']);
  const briefHelp = question === 'BREATHING' && memory.consciousness?.value === 'ALTERED'
    ? ' Si no responde, pide ayuda a alguien cercano y avisa al personal.' : '';
  return { text: question ? `${intro} ${questions[question]}${briefHelp}` :
    input.failed ? `${intro} Si hay peligro inmediato, avisa al personal del recinto y llama al 131 ante una emergencia médica.`
      : intro, question, protocol: null };
}

export function operationalSummary(memory: IncidentMemory) {
  const statements: string[] = [];
  const conscious = memory.consciousness?.value;
  if (conscious === 'UNRESPONSIVE') statements.push('Se reporta una persona que no responde');
  if (conscious === 'ALTERED') statements.push('Se reporta un posible desmayo');
  if (conscious === 'RESPONSIVE') statements.push('El último reporte indica que la persona responde');
  if (memory.breathing?.value === 'ABNORMAL') statements.push('Se reporta posible ausencia de respiración normal');
  if (memory.breathing?.value === 'NORMAL') statements.push('El último reporte indica respiración normal');
  if (memory.scene?.value === 'DANGER') statements.push('Hay un reporte de peligro en la escena');
  if (memory.risks.some(risk => risk.kind === 'BLEEDING')) statements.push('Se reportó sangrado abundante');
  if (memory.risks.some(risk => risk.kind === 'SEIZURE')) statements.push('Se reportaron convulsiones');
  if (memory.affectedCount) statements.push(`Cantidad reportada: ${memory.affectedCount.value}${memory.affectedCount.uncertain ? ' (incierta)' : ''}`);
  if (!statements.length && !memory.protocol && !memory.risks.length) return null;
  const pending = ['Verificación en terreno'];
  if (memory.contradictory) pending.push('Aclarar cambios o reportes contradictorios');
  if (conscious && !memory.breathing) pending.push('Comprobar respiración normal');
  return { situation: statements.join('. ') || 'Se registró una alerta de peligro', pending,
    guidance: memory.protocol, risks: memory.risks.slice(-12) };
}

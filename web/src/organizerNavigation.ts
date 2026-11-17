export const sectionLabels = {
  dashboard: 'Dashboard',
  incidentes: 'Incidentes',
  recinto: 'Recinto y zonas',
  personal: 'Personal',
  estadisticas: 'Estadísticas',
  anuncios: 'Anuncios y promociones',
} as const;

export type Section = keyof typeof sectionLabels;

export const queueHref = '#incidentes/cola';
export const historyHref = '#incidentes/historial';

export function sectionForHash(hash: string): Section {
  if (hash === queueHref || hash === historyHref) return 'incidentes';
  const target = hash.slice(1);
  return Object.hasOwn(sectionLabels, target) ? target as Section : 'dashboard';
}

export function resetSection() {
  window.history.replaceState(null, '', '#dashboard');
}

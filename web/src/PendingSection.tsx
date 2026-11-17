import type { Section } from './organizerNavigation.js';

type FutureSection = Exclude<Section, 'dashboard' | 'incidentes'>;

const descriptions: Record<FutureSection, string> = {
  recinto: 'Aquí podrás configurar el recinto, sus zonas, accesos, puntos médicos y referencias para la atención.',
  personal: 'Aquí podrás gestionar los guardias autorizados para el evento.',
  estadisticas: 'Aquí podrás consultar métricas históricas y gráficos del evento autorizado.',
  anuncios: 'Aquí podrás gestionar los anuncios y promociones del evento.',
};

export function PendingSection({ section }: { section: FutureSection }) {
  return <section className="panel future-panel" aria-labelledby="future-title">
    <h2 id="future-title">En desarrollo</h2>
    <p>{descriptions[section]}</p>
  </section>;
}

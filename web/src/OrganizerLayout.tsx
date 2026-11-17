import { useEffect, useRef, useState, type ReactNode } from 'react';
import { eventStatusLabels, type Event } from './api.js';
import { historyHref, queueHref, sectionLabels, type Section } from './organizerNavigation.js';

export function OrganizerLayout({ section, hash, event, available, onSelect, onSignOut, children }: {
  section: Section;
  hash: string;
  event: Event | undefined;
  available: Event[];
  onSelect: (id: string) => void;
  onSignOut: () => void;
  children: ReactNode;
}) {
  const [menuOpen, setMenuOpen] = useState(false);
  const menuButton = useRef<HTMLButtonElement>(null);
  const content = useRef<HTMLElement>(null);
  const eventId = event?.id;

  useEffect(() => {
    const anchor = hash === queueHref ? 'cola-activa' : hash === historyHref ? 'historial' : 'page-title';
    (document.getElementById(anchor) ?? content.current)?.focus();
  }, [hash, eventId]);

  return (
    <div className="organizer-layout">
      <a className="skip-link" href="#contenido" onClick={click => {
        click.preventDefault();
        content.current?.focus();
      }}>Saltar al contenido</a>
      <header className="workspace-header">
        {event && <button type="button" className="secondary menu-toggle" ref={menuButton}
          aria-expanded={menuOpen} aria-controls="workspace-nav"
          onClick={() => setMenuOpen(open => !open)}>Menú</button>}
        <strong className="mobile-brand">Discoprotect</strong>
        {event && <div className="event-heading">
          <p className="event-name">{event.name}</p>
          <p className="event-venue">{event.venue}</p>
        </div>}
        {event && Object.hasOwn(eventStatusLabels, event.status) &&
          <span className={`status event-state event-${event.status.toLowerCase()}`}>{eventStatusLabels[event.status]}</span>}
        <div className="header-actions">
          {available.length > 1 &&
            <div className="event-selector">
              <label htmlFor="event">Evento autorizado</label>
              <select id="event" value={event?.id ?? ''} onChange={change => {
                setMenuOpen(false);
                onSelect(change.currentTarget.value);
              }}>
                <option value="" disabled>Selecciona un evento</option>
                {available.map(option =>
                  <option key={option.id} value={option.id}>{option.name} · {option.venue}</option>)}
              </select>
            </div>}
          <button type="button" className="secondary" onClick={onSignOut}>Cerrar sesión</button>
        </div>
      </header>
      <aside className={`workspace-sidebar${menuOpen ? ' menu-open' : ''}`}>
        <div className="sidebar-brand"><strong>Discoprotect</strong><span>GestiónWeb</span></div>
        {event && <nav id="workspace-nav" aria-label="Secciones de GestiónWeb" onKeyDown={key => {
          if (key.key === 'Escape' && menuOpen) {
            setMenuOpen(false);
            menuButton.current?.focus();
          }
        }}>
          {Object.entries(sectionLabels).map(([id, label]) =>
            <a key={id} href={`#${id}`} aria-current={id === section ? 'page' : undefined}
              onClick={() => {
                if (menuOpen && hash === `#${id}`) menuButton.current?.focus();
                setMenuOpen(false);
              }}>{label}</a>)}
        </nav>}
      </aside>
      <main id="contenido" className="workspace-main" ref={content} tabIndex={-1}>
        <div className="workspace-content">
          <h1 id="page-title" tabIndex={-1}>{event ? sectionLabels[section] : 'GestiónWeb'}</h1>
          {children}
        </div>
      </main>
    </div>
  );
}

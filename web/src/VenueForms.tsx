import type { FormEvent, ReactNode } from 'react';
import { pointKinds, type OperationalPoint, type VenueChange, type VenueConfiguration, type VenueZone } from './venueApi.js';

export type VenueEditor = { entity: 'venue' } | { entity: 'zone'; item?: VenueZone } | { entity: 'point'; item?: OperationalPoint };

function Field({ name, label, value, maxLength = 160, multiline = false, required = false }: {
  name: string; label: string; value?: string | null; maxLength?: number; multiline?: boolean; required?: boolean;
}) {
  return <label className="venue-field">
    {label}
    {multiline ? <textarea name={name} defaultValue={value ?? ''} maxLength={maxLength} rows={3} required={required} />
      : <input name={name} defaultValue={value ?? ''} maxLength={maxLength} required={required} />}
  </label>;
}

function SelectField({ name, title, selected, children }: {
  name: string; title: string; selected: string; children: ReactNode;
}) {
  return <label className="venue-field">
    {title}
    <select name={name} defaultValue={selected} required>{children}</select>
  </label>;
}

export function VenueForm({ editor, config, disabled, onSave, onCancel }: {
  editor: VenueEditor; config: VenueConfiguration; disabled: boolean;
  onSave: (change: VenueChange) => void; onCancel: () => void;
}) {
  const { venue, zones } = config;
  const item = editor.entity === 'venue' ? undefined : editor.item;
  const title = editor.entity === 'venue' ? 'Editar recinto'
    : editor.entity === 'zone' ? item ? 'Editar zona' : 'Crear zona'
      : item ? 'Editar punto operativo' : 'Crear punto operativo';

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (disabled) return;
    const data = new FormData(event.currentTarget);
    const text = (key: string) => String(data.get(key) ?? '').trim();
    const notes = (key: string) => text(key) || null;
    const name = text('name');
    if (editor.entity === 'venue') {
      onSave({ entity: 'venue', fields: { name, venue: text('venue'), description: notes('description'),
        accessNotes: notes('accessNotes'), responseNotes: notes('responseNotes') } });
    } else if (editor.entity === 'zone') {
      onSave({ entity: 'zone', id: editor.item?.id, fields: { name, description: notes('description'), active: data.has('active') } });
    } else {
      onSave({ entity: 'point', id: editor.item?.id, fields: { name, kind: text('kind') as OperationalPoint['kind'],
        zoneId: text('zoneId'), reference: text('reference'), active: data.has('active') } });
    }
  }

  return <form className="venue-form" onSubmit={submit}>
    <h3>{title}</h3>
    <fieldset disabled={disabled}>
      {editor.entity === 'venue' ? <>
        <Field name="name" label="Nombre del evento" value={venue.name} required />
        <Field name="venue" label="Nombre del recinto" value={venue.venue} required />
        <Field name="description" label="Descripción operativa" value={venue.description} maxLength={600} multiline />
        <Field name="accessNotes" label="Referencias de acceso" value={venue.accessNotes} maxLength={300} multiline />
        <Field name="responseNotes" label="Referencias para responder a emergencias" value={venue.responseNotes} maxLength={300} multiline />
      </> : <>
        <Field name="name" label={editor.entity === 'zone' ? 'Lugar' : 'Nombre'} value={editor.item?.name} required />
        {editor.entity === 'zone' ? <>
          <Field name="description" label="Descripción" value={editor.item?.description} maxLength={240} multiline />
          <p className="venue-help">Incluye objetos y señas visibles, como un puesto de mote, un letrero amarillo o una reja negra.</p>
        </> : <>
          <SelectField name="kind" title="Tipo" selected={editor.item?.kind ?? 'MEDICAL'}>
            {Object.entries(pointKinds).map(([id, label]) => <option key={id} value={id}>{label}</option>)}
          </SelectField>
          <SelectField name="zoneId" title="Zona" selected={editor.item?.zoneId ?? ''}>
            <option value="" disabled>Selecciona una zona</option>
            {zones.map(zone => <option key={zone.id} value={zone.id}>{zone.name}{zone.active ? '' : ' · Inactiva'}</option>)}
          </SelectField>
          <Field name="reference" label="Referencia operativa" value={editor.item?.reference} required />
        </>}
        <label className="venue-check"><input type="checkbox" name="active" defaultChecked={editor.item?.active ?? true} /> Activo</label>
      </>}
    </fieldset>
    <div className="confirmation-actions">
      <button type="submit" disabled={disabled}>Guardar cambios</button>
      <button type="button" className="secondary" disabled={disabled} onClick={onCancel}>Cancelar</button>
    </div>
  </form>;
}

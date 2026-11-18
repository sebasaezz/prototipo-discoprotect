import { useEffect, useState, type FormEvent } from 'react';
import { ApiError, incidentLabels, messageFor, request, type Session } from './api.js';

type Member = {
  id: string; name: string; active: boolean; incidentId: string | null;
  incidentStatus: keyof typeof incidentLabels | null;
};

export function StaffPage({ eventId, session, onExpiry }: { eventId: string; session: Session; onExpiry: () => void }) {
  const [members, setMembers] = useState<Member[]>();
  const [selected, setSelected] = useState<Member>();
  const [action, setAction] = useState<{ personId: string; active: boolean; replacementId?: string; operationId: string }>();
  const [revision, setRevision] = useState(0);
  const [error, setError] = useState('');
  const path = `events/${encodeURIComponent(eventId)}/staff`;

  useEffect(() => {
    const controller = new AbortController();
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(8000)]);
    async function load() {
      try {
        if (action) await request(path, signal, session, { method: 'PATCH', body: action });
        const result = await request<{ members: Member[] }>(path, signal, session);
        if (controller.signal.aborted) return;
        setMembers(result.members);
        setSelected(undefined);
      } catch (failure) {
        if (controller.signal.aborted) return;
        if (failure instanceof ApiError && failure.status === 401) onExpiry();
        else setError(messageFor(failure));
      }
    }
    void load();
    return () => controller.abort();
  }, [action, onExpiry, path, revision, session]);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selected) return;
    const replacement = new FormData(event.currentTarget).get('replacement');
    setError('');
    setAction({ personId: selected.id, active: !selected.active, operationId: crypto.randomUUID(),
      ...(typeof replacement === 'string' && replacement ? { replacementId: replacement } : {}) });
  }

  return <section className="panel">
    <h2>Guardias del evento</h2>
    {error && <p role="alert" className="notice">{error}</p>}
    {!members && !error && <p role="status">Consultando personal…</p>}
    {members?.length === 0 && <p>Sin cuentas de guardia disponibles.</p>}
    <button className="secondary" onClick={() => {
      setAction(undefined);
      setSelected(undefined);
      setError('');
      setRevision(value => value + 1);
    }}>Actualizar personal</button>
    {members && members.length > 0 && <ul>
      {members.map(member => <li key={member.id}>
        <p><strong>{member.name}</strong> · Guardia · {member.active ? 'Autorizado' : 'Sin acceso'}</p>
        {member.incidentStatus && <p>{incidentLabels[member.incidentStatus]} · {member.incidentId}</p>}
        <button className="secondary" type="button" onClick={() => setSelected(member)}>
          {member.active ? 'Revocar acceso' : 'Autorizar'} a {member.name}
        </button>
      </li>)}
    </ul>}
    {selected && <form key={selected.id} onSubmit={submit}>
      <h3>Confirmar {selected.active ? 'revocación' : 'autorización'} de {selected.name}</h3>
      {selected.incidentId && <label>Guardia de reemplazo <select name="replacement" required defaultValue="">
        <option value="" disabled>Selecciona un guardia autorizado</option>
        {members?.filter(member => member.active && member.id !== selected.id && !member.incidentId)
          .map(member => <option key={member.id} value={member.id}>{member.name}</option>)}
      </select></label>}
      <button>Confirmar</button>
      <button className="secondary" type="button" onClick={() => setSelected(undefined)}>Cancelar</button>
    </form>}
  </section>;
}

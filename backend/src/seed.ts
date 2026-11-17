import { atomic, type Db } from './db.js';

export const DEMO_NFC = '8F31A20C779145D4B522F10492AA610E';

const profiles = [
  { key: 'antonia', name: 'Antonia Reyes', age: 22, blood: 'O+', allergies: ['Penicilina'], conditions: ['Diabetes tipo 1'], medications: ['Insulina'], contact: 'María Reyes', relationship: 'Madre', token: DEMO_NFC },
  { key: 'martin', name: 'Martín Ejemplo', age: 57, blood: 'A−', allergies: ['Maní'], conditions: ['Diabetes tipo 2'], medications: ['Metformina'], contact: 'Lucía Ejemplo', relationship: 'Contacto', token: '00000000000000000000000000000002' },
  { key: 'sofia', name: 'Sofía Ficticia', age: 26, blood: 'AB+', allergies: [], conditions: ['Epilepsia'], medications: ['Levetiracetam'], contact: 'Diego Ficticio', relationship: 'Contacto', token: '00000000000000000000000000000003' },
] as const;

function insertDemo(db: Db) {
  const now = new Date().toISOString();
  const addPerson = db.prepare('INSERT INTO people VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO NOTHING');
  const addMember = db.prepare('INSERT INTO event_memberships VALUES (?, ?, ?, ?) ON CONFLICT(id) DO NOTHING');
  const addCard = db.prepare('INSERT INTO emergency_cards VALUES (?, ?, ?, ?, ?, ?, ?, 1) ON CONFLICT(id) DO NOTHING');
  const addContact = db.prepare('INSERT INTO emergency_contacts VALUES (?, ?, ?, ?, ?)');
  const addCredential = db.prepare('INSERT INTO nfc_credentials VALUES (?, ?, ?, 1, ?) ON CONFLICT(id) DO NOTHING');

  db.prepare(`INSERT INTO events (id, name, venue, starts_at, ends_at, status)
    VALUES ('demo-festival', 'Festival Demo', 'Recinto ficticio', ?, ?, 'ACTIVE')
    ON CONFLICT(id) DO NOTHING`).run('2026-11-14T22:00:00.000Z', '2026-11-15T06:00:00.000Z');

  const addZone = db.prepare("INSERT INTO zones (event_id, id, name) VALUES ('demo-festival', ?, ?) ON CONFLICT(event_id, id) DO NOTHING");
  for (const [id, name] of [
    ['acceso', 'Acceso principal'], ['escenario', 'Escenario'], ['comida', 'Zona de comida'],
    ['banos', 'Baños'], ['general', 'Sector general'],
  ] as const) addZone.run(id, name);

  profiles.forEach((profile, index) => {
    const id = `demo-${profile.key}`;
    addPerson.run(id, profile.key, profile.name, profile.age, now, now);
    addMember.run(`member-${id}`, 'demo-festival', id, 'ATTENDEE');
    const created = addCard.run(`card-${id}`, id, profile.blood, JSON.stringify(profile.allergies),
      JSON.stringify(profile.conditions), JSON.stringify(profile.medications), now);
    if (created.changes) addContact.run(`contact-${id}`, id, profile.contact, profile.relationship, `+56 9 0000 000${index + 1}`);
    addCredential.run(`nfc-${id}`, id, Buffer.from(profile.token, 'hex'), now);
  });

  addPerson.run('demo-guardia', 'guardia', 'Guardia Demo', null, now, now);
  addMember.run('member-demo-guardia', 'demo-festival', 'demo-guardia', 'STAFF');
  addPerson.run('demo-guardia2', 'guardia2', 'Guardia Demo 2', null, now, now);
  addMember.run('member-demo-guardia2', 'demo-festival', 'demo-guardia2', 'STAFF');
  addPerson.run('demo-organizador', 'organizador', 'Organizador Demo', null, now, now);
  addMember.run('member-demo-organizador', 'demo-festival', 'demo-organizador', 'ORGANIZER');
}

export function seed(db: Db) {
  atomic(db, () => insertDemo(db));
}

export function resetDemo(db: Db) {
  atomic(db, () => {
    db.exec(`DELETE FROM events WHERE id = 'demo-festival';
      DELETE FROM people WHERE id IN ('demo-antonia', 'demo-martin', 'demo-sofia', 'demo-guardia', 'demo-guardia2', 'demo-organizador');`);
    insertDemo(db);
  });
}

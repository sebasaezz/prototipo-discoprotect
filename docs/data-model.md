# Modelo de datos

Los identificadores se tratan como cadenas opacas, los tiempos se guardan en UTC y SQLite valida claves foráneas en cada conexión. Las migraciones se aplican en orden y se registran en `schema_migrations`. Se usa WAL con un tiempo de espera de 5 segundos para escritores. Las escrituras de ficha y contacto pasan por transacciones inmediatas.

| Entidad | Datos y restricciones |
| --- | --- |
| Person | ID estable, clave demo única, nombre ficticio, edad opcional, creación y actualización. |
| EmergencyCard | Una por persona, grupo sanguíneo declarado, listas JSON de alergias, condiciones y medicamentos, fecha de actualización y versión entera. |
| EmergencyContact | Varios por ficha, con ID, nombre, relación y teléfono ficticio. |
| NfcCredential | Una por asistente, 16 bytes únicos como BLOB, indicador activo y creación. El transporte HTTP usa hexadecimal. |
| Event | ID, nombre, recinto, inicio, término y estado. El estado `ACTIVE` de la demo es explícito. |
| EventMembership | Una membresía por persona y evento. Roles `ATTENDEE`, `STAFF` y `ORGANIZER`. |
| DemoSession | Hash SHA-256 de un token aleatorio, cuenta, rol y vencimiento. La base guarda el hash; el valor Bearer se entrega al crear la sesión. |
| Zone | ID estable dentro del evento y nombre visible. El seed agrega cinco zonas para `demo-festival` y conserva las filas existentes. |
| Incident | UUID, evento, persona solicitante, zona opcional, referencia opcional de hasta 160 caracteres, estado `OPEN` o `CANCELLED`, UUID del cliente y tiempos UTC. |

La versión representa la ficha y sus contactos. Toda mutación exitosa aumenta la versión y actualiza la fecha. La resolución NFC devuelve ambos en una sola lectura consistente. El seed inserta los cinco perfiles y tres fichas ficticias. Las ejecuciones posteriores preservan ediciones y eliminaciones de contactos de las fichas existentes. La versión y los datos persistidos se usan durante la operación normal. El seed sirve para provisionar registros demo ausentes, como una membresía eliminada durante una prueba. La restauración reinicia las cinco cuentas de prueba, el evento demo y los registros relacionados por claves foráneas, incluidas las sesiones.

## Incidentes H5

`002_incidents.sql` se agrega sobre la base de H4. La clave foránea compuesta exige que la zona conocida pertenezca al mismo evento. Una zona nula representa ubicación desconocida. Los índices soportan el orden por fecha e ID para listados propios y del evento. La restricción única sobre persona y UUID de cliente aplica a todos sus eventos.

Una transacción inmediata compara o inserta el registro antes de responder. Cancelar actualiza el estado y la fecha del mismo incidente. Una migración futura puede usar su ID como referencia para un historial de transiciones. Los planos y la localización por coordenadas quedan para hitos futuros.

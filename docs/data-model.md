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
| Incident | UUID, evento, solicitante, zona y referencia opcionales, UUID del cliente, estado, versión, guardia asignado y tiempos UTC. La nota de cierre es opcional y de uso operativo. |
| IncidentTransition | Incidente, actor, estados anterior y nuevo, versión resultante y hora UTC. Las acciones del guardia agregan UUID de operación y huella del comando normalizado. |

La ficha y sus contactos comparten una versión. Toda mutación exitosa aumenta esa versión y actualiza la fecha. La resolución NFC devuelve ambos en una sola lectura consistente. El seed provisiona seis cuentas y tres fichas ficticias. Las ejecuciones posteriores preservan las ediciones y eliminaciones de contactos de las fichas existentes. La restauración explícita reinicia las cuentas, el evento demo y sus registros relacionados.

## Incidentes H5

`002_incidents.sql` se agrega sobre la base de H4. La clave foránea compuesta exige que la zona conocida pertenezca al mismo evento. Una zona nula representa ubicación desconocida. Los índices soportan el orden por fecha e ID para listados propios y del evento. La restricción única sobre persona y UUID de cliente aplica a todos sus eventos.

Una transacción inmediata compara o inserta el registro antes de responder. La cancelación conserva el ID del incidente.

## Incidentes H6

`003_incident_lifecycle.sql` reconstruye la tabla y conserva sus claves, índices y campos H5. Las filas heredadas conservan estado y fechas con versión inicial 1. El migrador rechaza referencias entrantes inesperadas y comprueba las filas de `PRAGMA foreign_key_check` dentro de `BEGIN IMMEDIATE`, antes de registrar la migración.

La creación lleva al estado `OPEN`. Una solicitud abierta puede cancelarse o ser asumida de forma exclusiva. El guardia asignado puede iniciar la atención y resolverla. La asignación, el inicio y la resolución registran sus propias horas. Una transición incrementa la versión y agrega una fila al historial en el mismo commit. El historial empieza con las transiciones efectuadas tras la migración. La versión resultante y el UUID de operación son únicos por incidente.

La asignación apunta a la persona y la autorización consulta su membresía vigente. Si el guardia se retira, el incidente conserva su responsable histórico. H8 puede agregar una transición autorizada de reasignación y conservar los responsables anterior y nuevo.

## Triaje H7

La migración `005` añade `incident_conversations` con clave del incidente y contadores de secuencia, e `incident_messages` con mensajes ordenados, autor e idempotencia. Los incidentes previos conservan los registros H5–H7. El historial se conserva al cancelar o resolver.

`004_incident_triage.sql` agrega motivo y descripción opcionales a `incidents` y crea las tablas de triaje sin reconstruir la tabla operativa. Los incidentes H6 existentes quedan en `MANUAL` con causa `LEGACY`. Las filas nuevas se crean con su triaje en el mismo commit. La versión de triaje se incrementa cuando se publica una sugerencia, se entra en fallback desde pendiente o se registra una revisión. La reserva interna del intento conserva la versión visible.

| Tabla | Datos y restricciones |
| --- | --- |
| `incident_triage` | Una fila por incidente. Estado, versión, causa de fallback o sugerencia validada, entrada en cola, intento reservado, finalización y metadatos del proveedor. Índice parcial para pendientes. |
| `incident_triage_reviews` | Historial de sólo inserción con actor, decisión, categoría y prioridad resultantes, fecha, versión aplicada, UUID de operación y huella del comando. Versión y UUID únicos dentro del incidente. |
| `triage_daily_usage` | Reservas de llamada por día UTC. La reserva se confirma antes de salir a la red y el contador se conserva al resetear la demo. |

Las filas de triaje y sus revisiones se eliminan en cascada al restaurar los incidentes de la demo. Cancelar un SOS conserva el relato, el triaje y el historial. La marca de intento se persiste antes de enviar para evitar llamadas duplicadas tras reiniciar.

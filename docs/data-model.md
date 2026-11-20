# Modelo de datos

## Migraciones de H10

| Migración | Cambios |
| --- | --- |
| `012_event_staff.sql` | Auditoría de altas, revocaciones y reemplazos de guardia. Identidad del actor y UUID de operación identifican el recibo. Se preservan el historial del incidente y su fase actual. |
| `013_event_services.sql` | Entradas únicas por asistente y evento, validación auditada con hash de código, promociones versionadas, invitaciones de acompañantes por hash y auditoría de accesos operativos. |
| `014_local_chat_locations.sql` | Indicios visuales por mensaje de chat con cita, zona probable opcional y versión del recinto. Los indicios ambiguos conservan la cita sin asignar zona. |

Al revocar personal, una transacción inmediata comprueba el reemplazo cuando corresponde, transfiere la asignación conservando fechas, retira la membresía y elimina las sesiones del guardia saliente. La unicidad de asignación continúa bajo el índice parcial de incidentes. Un nuevo inicio de sesión otorga los permisos actuales de la cuenta.

La credencial del QR es aleatoria de 192 bits y se conserva para que su titular pueda mostrarla. La auditoría guarda su hash, el evento y el operador que comprobó el ingreso. La validación y el consumo se registran en la misma transacción. Las invitaciones guardan solo el hash del código, una caducidad y el participante que aceptó. Un índice único impide vínculos simultáneos duplicados en ambas direcciones. Los registros operativos excluyen fichas médicas y contactos.

La base de demo de H10 se crea en una ruta nueva con `backend/scripts/seed-final.mjs`. Sus históricos son ficticios. Para actualizar una base de trabajo, haz una copia consistente con SQLite y valida `PRAGMA integrity_check` y `PRAGMA foreign_key_check` antes de usar la copia para probar las migraciones.

Los identificadores se tratan como cadenas opacas y los tiempos se guardan en UTC. Las conexiones de uso normal validan claves foráneas. Las migraciones se aplican en orden y se registran en `schema_migrations`. Se usa WAL con un tiempo de espera de 5 segundos para escritores. Las escrituras de ficha y contacto pasan por transacciones inmediatas.

| Entidad | Datos y restricciones |
| --- | --- |
| Person | ID estable, clave demo única, nombre ficticio, edad opcional, creación y actualización. |
| EmergencyCard | Una por persona, grupo sanguíneo declarado, listas JSON de alergias, condiciones y medicamentos, fecha de actualización y versión entera. |
| EmergencyContact | Varios por ficha, con ID, nombre, relación y teléfono ficticio. |
| NfcCredential | Una por asistente, 16 bytes únicos como BLOB, indicador activo y creación. El transporte HTTP usa hexadecimal. |
| Event | ID, nombre, recinto, inicio, término y estado. H8.2 agrega descripción, referencias de acceso y respuesta y versión de configuración. |
| EventMembership | Una membresía por persona y evento. Roles `ATTENDEE`, `STAFF` y `ORGANIZER`. |
| DemoSession | Hash SHA-256 de un token aleatorio, cuenta, rol y vencimiento. La base guarda el hash; el valor Bearer se entrega al crear la sesión. |
| Zone | ID estable dentro del evento, nombre del lugar, descripción de objetos y señas visibles y estado activo. Las columnas antiguas de función y referencia se conservan por compatibilidad. El seed agrega cinco zonas para `demo-festival` y conserva las filas existentes. |
| OperationalPoint | ID estable dentro del evento, zona del mismo evento, nombre, tipo, referencia y estado activo. |
| VenueConfigurationChange | Evento y revisión únicos, entidad, ID, acción, autor y fecha UTC registrados en la transacción de escritura. |
| Incident | UUID, evento, solicitante, zona y referencia opcionales, UUID del cliente, estado, versión, guardia asignado y tiempos UTC. El motivo es obligatorio para cierre o retiro y opcional para resolución. |
| IncidentTransition | Incidente, actor, estados anterior y nuevo, versión resultante y hora UTC. Las acciones versionadas del guardia y organizador agregan UUID de operación y huella del comando normalizado. |

La ficha y sus contactos comparten una versión. Toda mutación exitosa aumenta esa versión y actualiza la fecha. La resolución NFC devuelve ambos en una sola lectura consistente. El seed provisiona seis cuentas y tres fichas ficticias. Las ejecuciones posteriores preservan las ediciones y eliminaciones de contactos de las fichas existentes. La restauración explícita recrea las cuentas, el evento demo y sus registros relacionados con cascada. La cuota diaria y las tablas separadas de purga se conservan.

## Migraciones 001–011

| Migración | Datos que agrega o cambia |
| --- | --- |
| `001` | Personas, fichas, contactos, credenciales NFC, eventos, membresías y sesiones. |
| `002` | Incidentes y zonas. La FK compuesta exige que la zona conocida pertenezca al evento. |
| `003` | Versiones, asignación, horas operativas e historial de transiciones. Las filas H5 mantienen estado y fechas, con versión inicial 1. |
| `004` | Motivo y relato para clientes de triaje histórico, `incident_triage`, revisiones del guardia y cuota compartida `triage_daily_usage`. Los incidentes H6 migran a triaje manual con causa `LEGACY`. |
| `005` | `incident_conversations` y `incident_messages`. Una conversación por incidente aplicable. Mensajes con UUID, autor, secuencia, fecha y cuerpo. |
| `006` | `incident_chat_ai_runs`, por incidente y secuencia del asistente, y alias de zonas. Persiste reservas, estados, resultados, vínculo al mensaje publicado y métricas. |
| `007` | `incident_dispatch_reviews` con evaluación humana y motivo, y `dispatch_priority_policies` con versión y umbrales. |
| `008` | Reconstruye `incidents` e `incident_transitions` para admitir los nuevos estados y transiciones. Agrega un índice único parcial para guardias activos y las tablas de auditoría de purga. |
| `009` | Extiende eventos y zonas. Crea puntos operativos y auditoría versionada. Registra revisión del contexto de IA y copia el nombre de zona inferida de análisis anteriores según el catálogo al migrar. |
| `010` | Agrega `incident_chat_locations` para vincular pregunta, respuesta, zona y evidencia a las secuencias del chat. Las preguntas publicadas antes de actualizar quedan como historial. |
| `011` | Registra observaciones por mensaje, la pregunta efectivamente publicada y el protocolo aplicado. Agrega la secuencia observada a las revisiones de guardia y un contador diario independiente opcional para el chat. |

La 008 comprueba si un guardia tiene más de un incidente en `ASSIGNED` o `IN_PROGRESS`. Si detecta duplicados, aborta con un error antes de reconstruir las tablas. Requiere regularizar esas asignaciones de forma explícita. Durante la reconstrucción, el migrador desactiva temporalmente el enforcement de claves foráneas para preservar filas dependientes, ejecuta `PRAGMA foreign_key_check` antes de confirmar y vuelve a activar la validación. [Preparación de una base anterior](current-workflow.md#7-despliegue-local)

## Incidentes, chat y auditoría

`incidents` conserva el UUID, estado y versión operativa. La clave `(requester_person_id, client_request_id)` es única entre eventos. El índice `incidents_one_active_per_guard` se aplica solo a `ASSIGNED` e `IN_PROGRESS`. Los estados terminales `RESOLVED`, `CANCELLED`, `CLOSED` y `WITHDRAWN` conservan asignación, inicio y motivo donde corresponda. Dejan de ocupar ese índice. Cada transición actualiza la versión y registra actor, estados y fecha en `incident_transitions` dentro del mismo commit.

Cada chat conserva `last_seq` y `last_attendee_seq`. La secuencia y la clave de mensaje del cliente son únicas por conversación. Los mensajes de IA se enlazan con la fila del trabajo que los originó. Los turnos pueden quedar en cola, en ejecución, completados, en atención manual o descartados. Un análisis válido con respuesta descartada por seguridad puede conservarse sin un mensaje asociado. `triage_daily_usage` cuenta reservas por día UTC compartidas por ambas modalidades de IA. Los intentos se reservan antes de llamar al proveedor.

Las transiciones de ubicación del chat se anexan por secuencia. Cada pregunta guarda zona, revisión del catálogo y evidencia. La respuesta siguiente puede confirmar, rechazar o descartar la propuesta. Una indicación directa guarda el nombre y el ID canónicos. Los turnos con respuesta local se registran como `LOCAL_LOCATION` y quedan fuera del cálculo de prioridad de IA. El resultado de peligro anterior conserva su evidencia.

`incident_chat_observations` conserva hechos reportados con su secuencia, incertidumbre, pregunta precedente y, cuando corresponde, una respuesta local y el protocolo versionado. El despacho deriva el estado reciente y conserva señales críticas anteriores para auditoría. Las revisiones nuevas registran el último mensaje de asistente observado. Esto permite volver a elevar la prioridad ante evidencia nueva, sin reabrir evidencia ya evaluada.

Los turnos nuevos guardan el nombre de la zona de su catálogo de entrada si la inferencia se acepta. También guardan la revisión cuando reciben contexto ampliado. El análisis `h8.2-venue-v2` congela en `result_json.locationEvidence` la secuencia, cita del asistente y fragmento de descripción corroborado. Un cambio de configuración mientras el proveedor responde puede invalidar la propuesta antes de publicarla. Una vez guardado el análisis, sus citas y nombre se preservan aunque cambie la configuración. El esquema 009 admite esta evidencia en JSON y los turnos históricos conservan su formato.

Las revisiones de triaje H7 y de despacho tienen versiones propias, actor y UUID de operación. Despacho conserva prioridad humana, ubicación confirmada, evaluación de peligro y motivo. La prioridad efectiva se calcula en lectura con el estado y el tiempo de espera vigentes. La evidencia crítica se recupera de un análisis corroborado anterior y puede seguir aplicando tras nuevas interpretaciones. [Reglas y transiciones](current-workflow.md)

## Purga de demostración

La 008 agrega `incident_purges` para recibo, actor, evento, alcance, huella de selección, cantidad, fecha, archivo de respaldo y SHA-256. En la misma transacción, `incident_tombstones` conserva el ID y par de cuenta/UUID de creación del caso eliminado, ligados al recibo. Estos datos sobreviven al borrado del incidente y bloquean crear el mismo caso desde un reintento antiguo.

El borrado en cascada elimina mensajes, conversaciones, trabajos de IA, triaje, revisiones y transiciones solo de los incidentes elegidos. Conserva cuentas, eventos, zonas y credenciales. El respaldo SQLite se guarda fuera de Git. [Administración y purga](current-workflow.md#5-administración-de-incidentes)

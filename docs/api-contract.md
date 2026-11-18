# API REST v1

Contrato vigente hasta H8.2. Para el recorrido por pantallas, consulta el [flujo actual](current-workflow.md).

Base `/api/v1`. Las operaciones de datos requieren `Authorization: Bearer <token>`. Envía `Content-Type: application/json` para cuerpos JSON. Los nombres de campos son estables y los tiempos son ISO 8601 UTC.

| Operación | Cuerpo | Respuesta y acceso |
| --- | --- | --- |
| `GET /health` | — | 200 `{status,database}`. Pública. |
| `POST /demo/sessions` | `{account,pin?}` | 201 `{token, role, expiresAt}`. Sin Bearer. Invitados demo `antonia`, `martin`, `sofia`. `guardia`, `guardia2` y `organizador` requieren su PIN de entorno. Vence en 8 h. |
| `GET /me` | — | 200 `{person,role}`. Cualquier sesión. |
| `GET /me/emergency-card` | — | 200 ficha propia. Asistente. |
| `PUT /me/emergency-card` | `{version,bloodType,allergies,conditions,medications}` | 200 ficha actualizada. Asistente. Reemplazo completo. |
| `GET /me/contacts` | — | 200 `{contacts}`. Asistente. |
| `POST /me/contacts` | `{version,name,relationship,phone}` | 201 `{contact,cardVersion}`. Asistente. |
| `PUT /me/contacts/:id` | `{version,name,relationship,phone}` | 200 `{contact,cardVersion}`. Asistente y contacto propio. |
| `DELETE /me/contacts/:id` | `{version}` | 200 `{cardVersion}`. Asistente y contacto propio. |
| `GET /me/nfc-credential` | — | 200 `{id,token,active,createdAt}`. Asistente. |
| `GET /events` | — | 200 `{events}`. Devuelve las membresías de la cuenta. |
| `GET /events/:eventId/zones` | — | 200 `{zones:[{id,name}]}` de zonas activas. Membresía en el evento. |
| `GET /events/:eventId/venue` | — | 200 configuración del recinto. Guardia u organizador miembro. |
| `POST /events/:eventId/zones` | Campos y versión según Recinto y zonas. | 201 `{zone,version}`. Organizador miembro. |
| `PATCH /events/:eventId/zones/:zoneId` | Mismos campos que POST. | 200 `{zone,version}`. Organizador miembro. |
| `POST /events/:eventId/operational-points` | Campos y versión según Recinto y zonas. | 201 `{point,version}`. Organizador miembro. |
| `PATCH /events/:eventId/operational-points/:pointId` | Mismos campos que POST. | 200 `{point,version}`. Organizador miembro. |
| `POST /events/:eventId/incidents` | UUID de cliente y modalidad según [creación y conversación](#creación-y-conversación). | 201 creado o 200 en reenvío equivalente. Asistente miembro. |
| `GET /me/incidents?eventId=…` | — | 200 `{incidents}`. Solicitudes propias del asistente miembro. |
| `GET /me/incidents/:id` | — | 200 incidente propio. Asistente titular y miembro. |
| `GET /events/:eventId/incidents` | Query opcional `order=dispatch`. | 200 `{incidents}`. Guardia u organizador miembro. Para guardias añade `guardBusy` y `activeIncident`. |
| `GET /events/:eventId/incidents/:id` | — | 200 incidente operativo. Guardia u organizador miembro. |
| `GET /events/:eventId/incidents/:id/triage` | — | 200 `{triage,reviews,description?}`. Guardia u organizador miembro. Solo el guardia recibe el relato original. |
| `POST /events/:eventId/incidents/:id/triage/reviews` | `{triageVersion,operationId,decision,category?,urgency?}` | 200 `{review,triage,replayed}`. Guardia asignado con membresía vigente. |
| `POST /me/incidents/:id/cancel` | — | 200 incidente `CANCELLED`. Asistente titular y miembro. Acepta `OPEN`, `ASSIGNED` e `IN_PROGRESS`. Repetir una cancelación confirmada devuelve el mismo registro. |
| `POST /events/:eventId/incidents/:id/claim` | `{version,operationId}` | 200 `{operation,incident}`. Guardia miembro. `OPEN → ASSIGNED`, autoasignación. |
| `POST /events/:eventId/incidents/:id/start` | `{version,operationId}` | 200 `{operation,incident}`. Guardia asignado con membresía. `ASSIGNED → IN_PROGRESS`. |
| `POST /events/:eventId/incidents/:id/resolve` | `{version,operationId,resolutionNote?}` | 200 `{operation,incident}`. Guardia asignado con membresía. `IN_PROGRESS → RESOLVED`. |
| `POST /events/:eventId/incidents/:id/close` | `{version,operationId,resolutionNote}` | 200 `{operation,incident}`. Guardia asignado con membresía. `ASSIGNED` o `IN_PROGRESS → CLOSED`. |
| `POST /events/:eventId/incidents/:id/withdraw` | `{version,operationId,resolutionNote}` | 200 `{operation,incident}`. Organizador miembro. Cualquier activo pasa a `WITHDRAWN`. |
| `PATCH /events/:eventId` | `{name,venue,version,description,accessNotes,responseNotes}` o cuerpo histórico `{name,venue}`. | 200 evento modificado. La forma versionada agrega `version`. Organizador miembro del evento. |
| `POST /nfc/resolve` | `{token,eventId}` | 200 `{person,card,contacts}`. Rol `STAFF` con membresía de guardia en el evento solicitado. La credencial corresponde a un `ATTENDEE` de ese evento. |

- Persona `{id,account,name,age,createdAt,updatedAt}`. Edad en años o `null`.
- Ficha `{id,bloodType,allergies,conditions,medications,updatedAt,version}`. Las listas tienen hasta 30 textos de 160 caracteres. Grupo sanguíneo `A+`, `A−`, `B+`, `B−`, `AB+`, `AB−`, `O+`, `O−` o `null`.
- Contacto `{id,name,relationship,phone}`. `phone` usa de 3 a 32 caracteres, con dígitos y separadores de teléfono.
- Evento `{id,name,venue,startsAt,endsAt,status,role}`. Rol de la cuenta en ese evento.
- Incidente `{id,eventId,requesterName,zoneId,zoneName,locationText,status,createdAt,updatedAt,version,assignedAt,startedAt,resolvedAt}`. Los tiempos operativos pendientes son nulos. La vista propia incluye `requesterPersonId` y `clientRequestId`. La vista operativa agrega `assignedStaff: {id,name} | null` y `resolutionNote: string | null`. Los listados por defecto ordenan por creación e ID descendentes. Las vistas propia y operativa incluyen `hasConversation: true` cuando existe chat. Los tiempos de asignación e inicio se conservan si una solicitud ya asignada termina. `resolvedAt` corresponde a `RESOLVED`. El actor y la fecha de cada transición están en la auditoría operativa.
- `token` NFC son exactamente 32 dígitos hexadecimales sin separadores. Acepta minúsculas y mayúsculas. Con un guardia autorizado para el evento, un token inactivo, ajeno al evento o desconocido produce el mismo 404. El token NFC identifica al asistente y la sesión Bearer autoriza al guardia.

Los cuerpos de sesión, ficha y contactos se validan como JSON. Las escrituras de ficha y contactos requieren `version` entera positiva y se ejecutan en transacción. Una escritura exitosa incrementa la versión compartida exactamente en uno. El 409 incluye `details.currentVersion`. Para teléfonos, los separadores permitidos son espacio, `+`, paréntesis y guion, y debe haber al menos un dígito.

Los estados activos son `OPEN`, `ASSIGNED` e `IN_PROGRESS`. `RESOLVED`, `CANCELLED`, `CLOSED` y `WITHDRAWN` son terminales. Se conservan en el historial y cierran el chat. Un guardia tiene como máximo una asignación activa global, incluso entre eventos. En su listado, `guardBusy` indica ocupación y `activeIncident` contiene la atención activa con despacho, o `null` si no hay asignación o el guardia perdió acceso al evento de esa asignación.

Cada transición exitosa aumenta `version` en uno y registra actor y fecha. `resolve` admite motivo opcional, mientras que `close` y `withdraw` exigen una `resolutionNote` de 1 a 240 caracteres tras recortar. Ante un resultado incierto en una operación versionada, el cliente conserva versión, UUID y cuerpo. Fastify comprueba rol y membresía y reconoce la repetición por incidente, actor y comando normalizado antes de comparar la versión vigente.

`operation` contiene `{operationId,fromStatus,toStatus,appliedVersion,appliedAt,replayed}`. En un reintento exitoso, `replayed` es verdadero y los demás campos describen la operación original. `incident` contiene la vista operativa vigente, que puede haber avanzado. Por ejemplo, una repetición de `claim` puede confirmar `OPEN → ASSIGNED` mientras `incident.status` es `IN_PROGRESS`. La repetición exacta mantiene la versión y el historial. Las respuestas usan `Cache-Control: no-store`.

Los errores usan `{error:{code,message,details?}}`.

| HTTP | Código y motivo |
| --- | --- |
| 400 | `INVALID_INPUT` si falla la validación de dominio. Fastify puede devolver `INVALID_REQUEST` para problemas de transporte, con el estado correspondiente al formato o al tamaño. |
| 401 | `INVALID_CREDENTIALS` al iniciar sesión o `UNAUTHENTICATED` si el Bearer no es válido o está vencido. |
| 403 | `FORBIDDEN` por rol incompatible o `EVENT_FORBIDDEN` por falta de membresía autorizada. |
| 404 | `CARD_NOT_FOUND`, `CONTACT_NOT_FOUND`, `NFC_NOT_FOUND` o `INCIDENT_NOT_FOUND` cuando el recurso autorizado no está disponible. `NOT_FOUND` si la ruta no existe. |
| 409 | `VERSION_CONFLICT` para versión distinta de la vigente. `REQUEST_CONFLICT` para una clave reutilizada con otro actor o comando en un incidente, o un UUID SOS reutilizado con otros datos. `INCIDENT_NOT_OPEN` al cancelar un terminal distinto de `CANCELLED`. `INVALID_TRANSITION` si el estado impide la acción operativa. Los conflictos de estado o versión de incidentes incluyen `details.currentVersion` y `details.currentStatus`. |
| 403 | `PURGE_DISABLED` al solicitar purga fuera de su alcance demo. |
| 404 | `CONVERSATION_NOT_FOUND` cuando el chat solicitado no existe en el ámbito autorizado. |
| 409 | `GUARD_ALREADY_BUSY` en un segundo `claim`, `REQUEST_PURGED` al reutilizar una clave de creación purgada, `CHAT_CLOSED`, `CHAT_LIMIT`, `PURGE_SELECTION_CHANGED` o los conflictos de revisión descritos abajo. |
| 429 | `CHAT_RATE_LIMIT` con `Retry-After`. |
| 500 | `INTERNAL_ERROR` ante fallos internos y `PURGE_BACKUP_FAILED` si no se pudo verificar el respaldo. |

## Triaje histórico H7

Esta proyección conserva registros y clientes anteriores. El análisis conversacional y la prioridad efectiva se consultan en despacho.

- Motivo `reasonCode` admite `UNRESPONSIVE`, `IMMEDIATE_DANGER`, `ILLNESS_OR_INJURY`, `AGGRESSION_OR_HARASSMENT`, `CROWDING` y `OTHER`. El relato admite hasta 280 unidades UTF-16. Se conserva tal como se envió, incluida la puntuación y los saltos de línea. Un relato en blanco, un campo omitido y un campo nulo se tratan como ausencia.
- La vista operativa agrega `{reasonCode,dangerAlert,triage}` a los listados, detalles y respuestas de las acciones H6. `dangerAlert` es `UNRESPONSIVE`, `IMMEDIATE_DANGER` o `null`. Se deriva del motivo y es independiente de la clasificación. La vista propia del asistente conserva los campos H6.
- `triage` contiene `{triageVersion,status,suggestion,fallbackReason,fallback,latestReview}`. El estado es `PENDING`, `SUGGESTED` o `MANUAL`. `suggestion` solo tiene valor en `SUGGESTED`. `fallback` y `fallbackReason` solo tienen valor en `MANUAL`. El detalle añade `reviews` por versión ascendente, y `description` de tipo cadena o nulo únicamente con rol `STAFF`.
- Una sugerencia o fallback contiene `{category,urgency,summary,missingInformation,uncertainty}`. Categorías `MEDICAL`, `SECURITY`, `CROWD`, `OTHER`, `UNKNOWN`. Prioridades `IMMEDIATE`, `PRIORITY`, `UNDETERMINED`. Incertidumbre `MEDIUM` o `HIGH`. Resumen de hasta 220 caracteres y hasta tres códigos distintos entre `WHAT_HAPPENED`, `CURRENT_CONDITION`, `ONGOING_DANGER` y `PEOPLE_AFFECTED`.
- Una revisión contiene `{reviewVersion,reviewedTriageVersion,operationId,decision,category,urgency,reviewedAt,actor:{id,name}}`. `CONFIRM` requiere una sugerencia vigente y omite categoría y prioridad en el comando. `CORRECT` exige ambos valores. Un comando nuevo requiere `ASSIGNED` o `IN_PROGRESS`. Se reconoce una repetición exacta del mismo autor antes de verificar estado y versión, previa autorización vigente. Las revisiones actualizan la clasificación visible e incrementan solo `triageVersion`. La sugerencia original queda disponible.
- `TRIAGE_VERSION_CONFLICT` e `INVALID_TRIAGE_REVIEW` devuelven 409 con `details.currentTriageVersion` y `details.currentStatus`. Reusar un UUID con un comando distinto devuelve `REQUEST_CONFLICT`.

## Creación y conversación

AppAsistente usa `{clientRequestId,mode:"SOS"}`. Para integración, la API también admite `mode: "CHAT"` con `firstMessage: {clientMessageId,text}`. Las dos modalidades aceptan además `zoneId`, `locationText` y `reasonCode` opcionales y devuelven `{incident,conversation,message}`. El SOS devuelve `message: null` y una conversación vacía. Crear incidente y conversación se confirma antes de consultar OpenAI. El cuerpo histórico sin `mode` admite `description` y devuelve directamente el incidente propio.

Fastify normaliza los UUID a minúsculas y recorta `locationText` a un máximo de 160 caracteres. Una zona conocida debe pertenecer al evento. La clave de creación es única por asistente entre eventos. Para repetirla deben coincidir el evento, la modalidad, los campos aplicables y, para `CHAT`, el UUID y el texto del primer mensaje. Una modalidad distinta devuelve `REQUEST_CONFLICT`.

- Los prefijos son `/me/incidents/:id` para el asistente titular y `/events/:eventId/incidents/:id` para guardias miembros. `GET /conversation?afterSeq=0&limit=50` devuelve `{conversation,messages,nextAfterSeq,hasMore}`. El límite máximo es 100. Cada mensaje incluye `{id,seq,author:{type,displayName},text,createdAt}` y el UUID de cliente es visible solo para el autor.
- `POST /conversation/messages` del asistente acepta `{clientMessageId,text}` y devuelve `{message,conversation}` con 201 o 200 para una repetición exacta. El texto recortado admite 1–500 unidades UTF-16. UUID con otro contenido devuelve 409. Una escritura nueva en estado terminal devuelve `CHAT_CLOSED`. El límite de 20 mensajes nuevos por minuto por autor devuelve 429 y `Retry-After`. El límite total de 200 mensajes humanos devuelve `CHAT_LIMIT`.
- `conversation` contiene `{incidentId,control,controlVersion,guard,aiStatus,lastSeq}`. El control se deriva del estado operativo y usa su misma versión. Los valores son `AUTO` en incidente abierto, `STAFF` en asignado o en progreso y `CLOSED` en terminal.
- `aiStatus` es `PENDING` si el último turno está en cola o en ejecución, `READY` si publicó respuesta, `UNAVAILABLE` si el turno en `AUTO` terminó sin una respuesta publicable y `MANUAL` si no hay turno o el control ya pasó a personal o a terminal. Cada mensaje nuevo del asistente en `AUTO` se encola de forma individual. Las respuestas visibles tienen autor `AI`. Consulta [límites y fallback](current-workflow.md#3-turnos-de-ia-prioridad-y-ubicación).
- El mismo `POST /conversation/messages` bajo el prefijo del evento permite responder al guardia asignado. El `claim` agrega `conversation` si existe, registra la asignación e invalida los trabajos de chat pendientes en la misma transacción. Los resultados en vuelo se verifican antes de publicar. Un reenvío exacto de un mensaje confirmado puede recuperar su recibo después del cierre.


## Despacho

- `GET /events/:eventId/incidents?order=dispatch` entrega la cola ordenada por Fastify. La consulta histórica sin el parámetro conserva su orden por creación. Cada elemento de la cola incluye `dispatch` con prioridad efectiva, fuentes de IA y humana, razones, política, revisión, ubicaciones, análisis y tiempo transcurrido.
- Para incidentes con conversación, las respuestas de `claim`, `start`, `resolve`, `close` y `withdraw` incluyen el despacho recalculado. AppPersonal refresca el orden de la cola tras la operación. Las apps conservan en memoria la clave y el cuerpo de cada mensaje incierto al cerrar y reabrir una conversación de la misma sesión.
- El despacho expone prioridad efectiva, fuente, razones, sugerencias inicial y reciente, prioridad humana, tiempo transcurrido, revisión y versión de política. Incluye `declaredLocation`, `inferredLocation`, `confirmedLocation`, resumen y `criticalEvidence` con las secuencias del mensaje y del análisis corroborado. El historial se entrega después de la cola activa, ordenado por última transición descendente. Las [reglas de prioridad y escalada](current-workflow.md#3-turnos-de-ia-prioridad-y-ubicación) describen el orden activo y el piso crítico.
- Cuando hay una inferencia nueva respaldada, `analysis.locationEvidence` contiene `{messageSeq,quote,feature}`. `quote` cita el relato filtrado del asistente y `feature` cita la descripción usada en el análisis o es `null` si la correspondencia se basó en el nombre, un alias o una referencia antigua. La ubicación sigue siendo inferida aunque el chat pida confirmarla. Las respuestas anteriores pueden omitir el campo.
- `attendeeLocation` es nulo o contiene `{zoneId,zoneName,source,messageSeq,evidence}`. `source` es `CONFIRMED_AI` si el asistente confirma la propuesta pendiente o `INDICATED` si indica un nombre canónico inequívoco del catálogo activo. `messageSeq` identifica la respuesta o indicación y `evidence` conserva la cita original con su secuencia y referencia visual cuando corresponda. La vista de despacho da precedencia a `confirmedLocation` del guardia.
- En `AUTO`, la primera respuesta posterior a una pregunta publicada puede resolverla localmente. Un «sí» inequívoco requiere que el catálogo siga vigente y la zona siga activa. Un «no» descarta la propuesta y pide otra seña. Un turno local publica su respuesta en la misma transacción y deja el chat `READY`. El reenvío del mensaje conserva su recibo sin generar otra respuesta.
- `GET /events/:eventId/incidents/:id/dispatch` devuelve `{dispatch,reviews,transitions}` a guardias y organizadores miembros. Para guardias añade `zones`. El organizador puede consultar el resumen interno y la auditoría. El hilo de mensajes se sirve en las rutas del asistente y el guardia.
- `POST /events/:eventId/incidents/:id/dispatch/reviews` acepta `{expectedRevision,operationId,humanPriority,confirmedLocation,dangerAssessment,reason}`. La revisión parte en 0. Prioridad y ubicación aceptan nulo. La ubicación no nula exige `{zoneId,text}` con al menos un dato y referencia de hasta 160 caracteres. El peligro acepta `UNCHANGED`, `CONFIRMED` o `CLEARED`. El motivo requiere 1–240 caracteres. El guardia asignado en estado activo recibe `{review,dispatch,replayed}`. La revisión obsoleta devuelve `409 DISPATCH_CONFLICT` con la revisión vigente.

## Administración y purga

El retiro administrativo usa `/withdraw` y conserva incidente, chat y auditoría. Los endpoints de purga se reservan al organizador del evento sembrado `demo-festival`, con una SQLite en disco y `DEMO_PURGE_ENABLED=true`.

| Operación | Solicitud | Respuesta |
| --- | --- | --- |
| `GET /events/:eventId/incidents/demo-purge` | Query opcional `incidentId`. Omitirlo selecciona el evento completo. | 200 `{enabled,count,selectionHash}`. Una purga desactivada devuelve `false`, 0 y `null`. |
| `POST /events/:eventId/incidents/demo-purge` | `{incidentId?,operationId,selectionHash,confirmation:"ELIMINAR"}`. | 200 `{count,backupName,backupSha256,replayed}`. Una selección vacía u obsoleta devuelve `PURGE_SELECTION_CHANGED`. |

Fastify verifica el respaldo antes de borrar y conserva la auditoría de purga. El UUID de operación permite recuperar el recibo. El reenvío de la creación original devuelve `REQUEST_PURGED` por su tombstone. [Flujo de administración](current-workflow.md#5-administración-de-incidentes)

## Recinto y zonas

- `GET /events/:eventId/venue` devuelve `{eventId,venue,zones,points}`. Para `ORGANIZER` agrega la versión y devuelve también registros inactivos. Para `STAFF` devuelve zonas activas y puntos activos en zonas activas. `ATTENDEE` recibe 403 en esta ruta y conserva el catálogo básico de zonas activas.
- `venue` contiene `{name,venue,description,accessNotes,responseNotes}`. Nombre de evento y recinto admiten hasta 160 caracteres. Descripción admite hasta 600 y las dos referencias hasta 300 cada una. Los tres campos operativos admiten `null`.
- La escritura de zonas usa `{name,description,active,version}`. El nombre es el lugar que reconoce el personal y admite hasta 160 caracteres. La descripción reúne objetos y señas visibles, admite hasta 240 caracteres y puede ser `null`. Se conserva el ID estable. La lectura mantiene `kind` y `reference` por compatibilidad y los cuerpos antiguos aún pueden incluirlos. Al omitirlos en una edición se preservan sus valores existentes. Para una zona nueva se guardan `OTHER` y `null`.
- Punto `{id,name,kind,zoneId,zoneName,reference,active}`. El cuerpo de escritura omite el ID y `zoneName` y agrega `version`. `kind` admite `MEDICAL`, `SECURITY`, `ENTRANCE`, `EXIT` o `MEETING`. Nombre y referencia obligatorios de hasta 160 caracteres. La zona asociada debe pertenecer al mismo evento.
- La versión corresponde al conjunto de configuración. Una escritura exitosa la incrementa en uno y registra entidad, acción, autor y fecha. Una versión obsoleta devuelve 409 `VENUE_CONFLICT` con `details.currentVersion`. El cuerpo histórico de edición de evento actualiza solo nombre y recinto y también incrementa la versión.
- Desactivar preserva el ID y las referencias históricas. Crear una solicitud con una zona inactiva devuelve 400 `INVALID_INPUT`. Una repetición exacta de una creación ya confirmada sigue devolviendo su recibo. Los puntos de una zona desactivada quedan fuera de las vistas operativas hasta que la zona se reactive.

# API REST v1

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
| `GET /events/:eventId/zones` | — | 200 `{zones:[{id,name}]}`. Membresía en el evento. |
| `POST /events/:eventId/incidents` | `{clientRequestId,zoneId?,locationText?,reasonCode?,description?}` | 201 incidente creado o 200 repetición equivalente. Asistente miembro. |
| `GET /me/incidents?eventId=…` | — | 200 `{incidents}`. Solicitudes propias del asistente miembro. |
| `GET /me/incidents/:id` | — | 200 incidente propio. Asistente titular y miembro. |
| `GET /events/:eventId/incidents` | — | 200 `{incidents}`. Guardia u organizador miembro. |
| `GET /events/:eventId/incidents/:id` | — | 200 incidente operativo. Guardia u organizador miembro. |
| `GET /events/:eventId/incidents/:id/triage` | — | 200 `{triage,reviews,description?}`. Guardia u organizador miembro. Solo el guardia recibe el relato original. |
| `POST /events/:eventId/incidents/:id/triage/reviews` | `{triageVersion,operationId,decision,category?,urgency?}` | 200 `{review,triage,replayed}`. Guardia asignado con membresía vigente. |
| `POST /me/incidents/:id/cancel` | — | 200 incidente cancelado. Asistente creador y miembro del evento. |
| `POST /events/:eventId/incidents/:id/claim` | `{version,operationId}` | 200 `{operation,incident}`. Guardia miembro. `OPEN → ASSIGNED`, autoasignación. |
| `POST /events/:eventId/incidents/:id/start` | `{version,operationId}` | 200 `{operation,incident}`. Guardia asignado con membresía. `ASSIGNED → IN_PROGRESS`. |
| `POST /events/:eventId/incidents/:id/resolve` | `{version,operationId,resolutionNote?}` | 200 `{operation,incident}`. Guardia asignado con membresía. `IN_PROGRESS → RESOLVED`. |
| `PATCH /events/:eventId` | `{name,venue}` | 200 evento modificado. Organizador miembro del evento. |
| `POST /nfc/resolve` | `{token,eventId}` | 200 `{person,card,contacts}`. Rol `STAFF` con membresía de guardia en el evento solicitado. La credencial corresponde a un `ATTENDEE` de ese evento. |

- Persona `{id,account,name,age,createdAt,updatedAt}`. Edad en años o `null`.
- Ficha `{id,bloodType,allergies,conditions,medications,updatedAt,version}`. Las listas tienen hasta 30 textos de 160 caracteres. Grupo sanguíneo `A+`, `A−`, `B+`, `B−`, `AB+`, `AB−`, `O+`, `O−` o `null`.
- Contacto `{id,name,relationship,phone}`. `phone` usa de 3 a 32 caracteres, con dígitos y separadores de teléfono.
- Evento `{id,name,venue,startsAt,endsAt,status,role}`. Rol de la cuenta en ese evento.
- Incidente `{id,eventId,requesterName,zoneId,zoneName,locationText,status,createdAt,updatedAt,version,assignedAt,startedAt,resolvedAt}`. Los tiempos operativos pendientes son nulos. La vista propia incluye `requesterPersonId` y `clientRequestId`. La vista operativa agrega `assignedStaff: {id,name} | null` y `resolutionNote: string | null`. Los listados se ordenan por creación e ID descendentes.
- `token` NFC son exactamente 32 dígitos hexadecimales sin separadores. Acepta minúsculas y mayúsculas. Con un guardia autorizado para el evento, un token inactivo, ajeno al evento o desconocido produce el mismo 404. El token NFC identifica al asistente y la sesión Bearer autoriza al guardia.

Los cuerpos de sesión, ficha y contactos se validan como JSON. Las escrituras de ficha y contactos requieren `version` entera positiva y se ejecutan en transacción. Una escritura exitosa incrementa la versión compartida exactamente en uno. El 409 incluye `details.currentVersion`. Para teléfonos, los separadores permitidos son espacio, `+`, paréntesis y guion, y debe haber al menos un dígito.

El SOS requiere un UUID generado por el cliente. El servidor normaliza la clave a minúsculas y recorta la referencia manual a efectos de comparación, con un límite de 160 caracteres tras recortar. Zona omitida o nula representa ubicación desconocida y puede acompañarse de una referencia. Una referencia vacía equivale a nula. La zona debe pertenecer al evento. Un reenvío con la misma clave por la misma persona devuelve el registro y su estado vigente si coinciden evento, zona, referencia, motivo y descripción. La cancelación cambia `OPEN` a `CANCELLED` y conserva el ID.

Cada transición exitosa aumenta `version` en uno. `CANCELLED` y `RESOLVED` son terminales. La nota opcional se recorta a efectos de comparación, admite hasta 240 caracteres tras recortar y se guarda como nula si está vacía. El guardia envía la versión observada y un UUID `operationId`; ante un resultado incierto conserva versión, UUID y cuerpo. El servidor verifica rol y membresía y reconoce el reintento por incidente, actor y comando normalizado antes de comparar la versión vigente.

`operation` contiene `{operationId,fromStatus,toStatus,appliedVersion,appliedAt,replayed}`. En un reintento exitoso, `replayed` es verdadero y los demás campos describen la operación original. `incident` contiene la vista operativa vigente, que puede haber avanzado. Por ejemplo, una repetición de `claim` puede confirmar `OPEN → ASSIGNED` mientras `incident.status` es `IN_PROGRESS`. La repetición exacta mantiene la versión y el historial. Las respuestas usan `Cache-Control: no-store`.

Los errores usan `{error:{code,message,details?}}`.

| HTTP | Código y motivo |
| --- | --- |
| 400 | `INVALID_INPUT` si falla la validación de dominio. Fastify puede devolver `INVALID_REQUEST` para problemas de transporte, con el estado correspondiente al formato o al tamaño. |
| 401 | `INVALID_CREDENTIALS` al iniciar sesión o `UNAUTHENTICATED` si el Bearer no es válido o está vencido. |
| 403 | `FORBIDDEN` por rol incompatible o `EVENT_FORBIDDEN` por falta de membresía autorizada. |
| 404 | `CARD_NOT_FOUND`, `CONTACT_NOT_FOUND`, `NFC_NOT_FOUND` o `INCIDENT_NOT_FOUND` cuando el recurso autorizado no está disponible. `NOT_FOUND` si la ruta no existe. |
| 409 | `VERSION_CONFLICT` para versión distinta de la vigente. `REQUEST_CONFLICT` para una clave reutilizada con otro actor o comando en un incidente, o un UUID SOS reutilizado con otros datos. `INCIDENT_NOT_OPEN` al cancelar desde otro estado. `INVALID_TRANSITION` si el estado impide la acción operativa. Los conflictos de estado o versión de incidentes incluyen `details.currentVersion` y `details.currentStatus`. |
| 500 | `INTERNAL_ERROR` ante fallos internos. |

## Triaje H7

- Motivo `reasonCode` admite `UNRESPONSIVE`, `IMMEDIATE_DANGER`, `ILLNESS_OR_INJURY`, `AGGRESSION_OR_HARASSMENT`, `CROWDING` y `OTHER`. El relato admite hasta 280 unidades UTF-16. Se conserva tal como se envió, incluida la puntuación y los saltos de línea. Un relato en blanco, un campo omitido y un campo nulo se tratan como ausencia.
- La vista operativa agrega `{reasonCode,dangerAlert,triage}` a los listados, detalles y respuestas de las acciones H6. `dangerAlert` es `UNRESPONSIVE`, `IMMEDIATE_DANGER` o `null`. Se deriva del motivo y es independiente de la clasificación. La vista propia del asistente conserva los campos H6.
- `triage` contiene `{triageVersion,status,suggestion,fallbackReason,fallback,latestReview}`. El estado es `PENDING`, `SUGGESTED` o `MANUAL`. `suggestion` solo tiene valor en `SUGGESTED`. `fallback` y `fallbackReason` solo tienen valor en `MANUAL`. El detalle añade `reviews` por versión ascendente, y `description` de tipo cadena o nulo únicamente con rol `STAFF`.
- Una sugerencia o fallback contiene `{category,urgency,summary,missingInformation,uncertainty}`. Categorías `MEDICAL`, `SECURITY`, `CROWD`, `OTHER`, `UNKNOWN`. Prioridades `IMMEDIATE`, `PRIORITY`, `UNDETERMINED`. Incertidumbre `MEDIUM` o `HIGH`. Resumen de hasta 220 caracteres y hasta tres códigos distintos entre `WHAT_HAPPENED`, `CURRENT_CONDITION`, `ONGOING_DANGER` y `PEOPLE_AFFECTED`.
- Una revisión contiene `{reviewVersion,reviewedTriageVersion,operationId,decision,category,urgency,reviewedAt,actor:{id,name}}`. `CONFIRM` requiere una sugerencia vigente y omite categoría y prioridad en el comando. `CORRECT` exige ambos valores. Un comando nuevo requiere `ASSIGNED` o `IN_PROGRESS`. Se reconoce una repetición exacta del mismo autor antes de verificar estado y versión, previa autorización vigente. Las revisiones actualizan la clasificación visible e incrementan solo `triageVersion`. La sugerencia original queda disponible.
- `TRIAGE_VERSION_CONFLICT` e `INVALID_TRIAGE_REVIEW` devuelven 409 con `details.currentTriageVersion` y `details.currentStatus`. Reusar un UUID con un comando distinto devuelve `REQUEST_CONFLICT`.

## Conversación H7.2

La creación de incidentes admite `mode: "CHAT"` con `firstMessage: {clientMessageId,text}` o `mode: "SOS"` sin texto. Ambas modalidades devuelven `{incident,conversation,message}` con 201 o 200 en un reenvío equivalente. El SOS devuelve `message: null`. El servidor escribe todo en una transacción. La modalidad histórica conserva su respuesta y el cliente descubre las conversaciones en los incidentes con `hasConversation: true`.

- Los prefijos son `/me/incidents/:id` para el asistente titular y `/events/:eventId/incidents/:id` para guardias miembros. `GET /conversation?afterSeq=0&limit=50` devuelve `{conversation,messages,nextAfterSeq,hasMore}`. El límite máximo es 100. Cada mensaje incluye `{id,seq,author:{type,displayName},text,createdAt}` y el UUID de cliente es visible solo para el autor.
- `POST /conversation/messages` del asistente acepta `{clientMessageId,text}` y devuelve `{message,conversation}` con 201 o 200 para una repetición exacta. El texto recortado admite 1–500 unidades UTF-16. UUID con otro contenido devuelve 409. Una escritura nueva en estado terminal devuelve `CHAT_CLOSED`. El límite de 20 mensajes nuevos por minuto por autor devuelve 429 y `Retry-After`. El límite total de 200 mensajes humanos devuelve `CHAT_LIMIT`.
- `conversation` contiene `{incidentId,control,controlVersion,guard,aiStatus,lastSeq}`. El control se deriva del estado H6 y usa su misma versión. Los valores son `AUTO` en incidente abierto, `STAFF` en asignado o en progreso y `CLOSED` en terminal.

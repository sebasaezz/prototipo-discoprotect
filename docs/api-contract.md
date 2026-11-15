# API REST v1

Base `/api/v1`. Las operaciones de datos requieren `Authorization: Bearer <token>`. Envía `Content-Type: application/json` para cuerpos JSON. Los nombres de campos son estables y los tiempos son ISO 8601 UTC.

| Operación | Cuerpo | Respuesta y acceso |
| --- | --- | --- |
| `GET /health` | — | 200 `{status,database}`. Pública. |
| `POST /demo/sessions` | `{account,pin?}` | 201 `{token, role, expiresAt}`. Sin Bearer. Invitados demo `antonia`, `martin`, `sofia`. `guardia` y `organizador` requieren su PIN de entorno. Vence en 8 h. |
| `GET /me` | — | 200 `{person,role}`. Cualquier sesión. |
| `GET /me/emergency-card` | — | 200 ficha propia. Asistente. |
| `PUT /me/emergency-card` | `{version,bloodType,allergies,conditions,medications}` | 200 ficha actualizada. Asistente. Reemplazo completo. |
| `GET /me/contacts` | — | 200 `{contacts}`. Asistente. |
| `POST /me/contacts` | `{version,name,relationship,phone}` | 201 `{contact,cardVersion}`. Asistente. |
| `PUT /me/contacts/:id` | `{version,name,relationship,phone}` | 200 `{contact,cardVersion}`. Asistente y contacto propio. |
| `DELETE /me/contacts/:id` | `{version}` | 200 `{cardVersion}`. Asistente y contacto propio. |
| `GET /me/nfc-credential` | — | 200 `{id,token,active,createdAt}`. Asistente. |
| `GET /events` | — | 200 `{events}`. Devuelve las membresías de la cuenta. |
| `PATCH /events/:eventId` | `{name,venue}` | 200 evento modificado. Organizador miembro del evento. |
| `POST /nfc/resolve` | `{token,eventId}` | 200 `{person,card,contacts}`. Rol `STAFF` con membresía de guardia en el evento solicitado. La credencial corresponde a un `ATTENDEE` de ese evento. |

- Persona `{id,account,name,age,createdAt,updatedAt}`. Edad en años o `null`.
- Ficha `{id,bloodType,allergies,conditions,medications,updatedAt,version}`. Las listas tienen hasta 30 textos de 160 caracteres. Grupo sanguíneo `A+`, `A−`, `B+`, `B−`, `AB+`, `AB−`, `O+`, `O−` o `null`.
- Contacto `{id,name,relationship,phone}`. `phone` usa de 3 a 32 caracteres, con dígitos y separadores de teléfono.
- Evento `{id,name,venue,startsAt,endsAt,status,role}`. Rol de la cuenta en ese evento.
- `token` NFC son exactamente 32 dígitos hexadecimales sin separadores. Acepta minúsculas y mayúsculas. Con un guardia autorizado para el evento, un token inactivo, ajeno al evento o desconocido produce el mismo 404. El token NFC identifica al asistente y la sesión Bearer autoriza al guardia.

Los cuerpos de sesión, ficha y contactos se validan como JSON. Las escrituras de ficha y contactos requieren `version` entera positiva y se ejecutan en transacción. Una escritura exitosa incrementa la versión compartida exactamente en uno. El 409 incluye `details.currentVersion`. Para teléfonos, los separadores permitidos son espacio, `+`, paréntesis y guion, y debe haber al menos un dígito.

Los errores usan `{error:{code,message,details?}}`.

| HTTP | Código y motivo |
| --- | --- |
| 400 | `INVALID_INPUT` si falla la validación de dominio. Fastify puede devolver `INVALID_REQUEST` para problemas de transporte, con el estado correspondiente al formato o al tamaño. |
| 401 | `INVALID_CREDENTIALS` al iniciar sesión o `UNAUTHENTICATED` si el Bearer no es válido o está vencido. |
| 403 | `FORBIDDEN` por rol incompatible o `EVENT_FORBIDDEN` por falta de membresía autorizada. |
| 404 | `CARD_NOT_FOUND`, `CONTACT_NOT_FOUND` o `NFC_NOT_FOUND` cuando el recurso autorizado no está disponible. `NOT_FOUND` si la ruta no existe. |
| 409 | `VERSION_CONFLICT` si la versión de escritura es anterior. AppAsistente conserva el borrador, consulta la versión vigente y exige revisión. |
| 500 | `INTERNAL_ERROR` ante fallos internos. |

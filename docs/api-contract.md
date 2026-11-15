# API REST v1

Base `/api/v1`. Las operaciones de datos requieren `Authorization: Bearer <token>`. Envía `Content-Type: application/json` para cuerpos JSON. Los nombres de campos son estables y los tiempos son ISO 8601 UTC.

| Operación | Cuerpo | Respuesta y acceso |
| --- | --- | --- |
| `GET /health` | — | 200 `{status,database}`. Pública. |
| `POST /demo/sessions` | `{account,pin?}` | 201 `{token, role, expiresAt}`. Invitados demo `antonia`, `martin`, `sofia`. `guardia` y `organizador` requieren su PIN de entorno. Vence en 8 h. |
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
| `POST /nfc/resolve` | `{token,eventId}` | 200 `{person,card,contacts}`. Guardia miembro del mismo evento que el asistente. |

- Persona `{id,account,name,age,createdAt,updatedAt}`. Edad en años o `null`.
- Ficha `{id,bloodType,allergies,conditions,medications,updatedAt,version}`. Las listas tienen hasta 30 textos de 160 caracteres. Grupo sanguíneo `A+`, `A−`, `B+`, `B−`, `AB+`, `AB−`, `O+`, `O−` o `null`.
- Contacto `{id,name,relationship,phone}`. `phone` usa de 3 a 32 caracteres, con dígitos y separadores de teléfono.
- Evento `{id,name,venue,startsAt,endsAt,status,role}`. Rol de la cuenta en ese evento.
- `token` NFC son exactamente 32 dígitos hexadecimales sin separadores. Acepta minúsculas y mayúsculas. Un token inactivo, ajeno al evento o desconocido produce el mismo 404. El token NFC identifica al asistente y la sesión Bearer autoriza al guardia.

Los errores usan `{error:{code,message,details?}}`. Códigos de estado 400 para entrada inválida, 401 para sesión o credenciales inválidas, 403 para rol o evento no autorizado, 404 para recurso ausente y 409 `VERSION_CONFLICT` para ediciones sobre una ficha anterior. Ante 409, AppAsistente mantiene el borrador, consulta la versión vigente y exige revisión antes de un nuevo guardado. La versión se comparte entre ficha y contactos. `DELETE /me/contacts/:id` recibe el JSON con la versión, igual que las demás mutaciones.

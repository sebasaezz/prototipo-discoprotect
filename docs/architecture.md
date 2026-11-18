# Arquitectura

El [flujo actual](current-workflow.md) describe la operación, el despliegue local y los permisos hasta H8.2. Esta página ubica las responsabilidades en el código.

| Componente | Responsabilidad |
| --- | --- |
| `protocol` | Construcción y análisis de APDU y sesión de tarjeta pura JVM. |
| `emitter` | HCE, identificación NFC, ficha y contactos propios, SOS de botón único, chat y cancelación. |
| `reader` | Reader Mode, IsoDep, resolución autorizada de fichas, cola, atención activa, chat y cierre. |
| `android-ui` | Componentes Compose compartidos para incidentes, chat y despacho. |
| `backend` | REST, permisos, operaciones y auditoría sobre SQLite, y trabajadores de IA. |
| `web` | Dashboard del evento autorizado, cola, historial y auditoría, retiro administrativo y purga demo mediante React, TypeScript y Vite. |

## Clientes y fronteras

`AttendeeController` y `ReaderController` coordinan sesión, evento, red y acciones. `HttpAttendeeApi` y `HttpStaffApi` usan OkHttp cancelable y Kotlin Serialization. Las apps conservan en memoria el Bearer y el estado pendiente. La web mantiene el Bearer en estado React y usa el proxy de Vite. Las lecturas se invalidan al cambiar el contexto.

`PersonCardService` atiende APDU desde el registro atómico `HceIdentityStore`. `CardSession` vincula SELECT y GET a la misma generación. `NfcReader` cierra IsoDep antes de entregar el ID al lector para su resolución por HTTP. `ProfileRepository` distingue la fuente remota de la demo local explícita. [Contrato NFC](nfc-contract.md)

### GestiónWeb

`OrganizerWorkspace` resuelve el contexto con las membresías `ORGANIZER` devueltas por Fastify. `OrganizerLayout` presenta la navegación y el encabezado. `useEventIncidents` comparte una sola consulta periódica entre Dashboard e Incidentes y cancela las lecturas al cambiar de sesión o evento. `DashboardOverview` deriva los indicadores y la vista previa de esa respuesta. `IncidentAdministration` conserva el comando pendiente mientras el organizador navega dentro del mismo evento. Un retiro o una purga confirmados invalidan la lectura anterior y solicitan datos vigentes. Las rutas y los permisos siguen definidos por el servidor. [Contrato API](api-contract.md)

`VenuePage` presenta y edita el recinto, sus zonas y puntos operativos. Si hay conflicto o resultado de escritura incierto, pide consultar la configuración vigente antes de volver a guardar. AppPersonal consulta el recinto activo bajo demanda desde el detalle de la solicitud.

## Fastify y SQLite

`main.ts` abre SQLite, aplica migraciones e inicia Fastify y los trabajadores. `app.ts` agrupa rutas y validación Zod. `auth.ts` verifica sesiones, roles y membresías. `records.ts` opera sobre cuentas, fichas, contactos y eventos. `db.ts` controla transacciones y migraciones.

`incidents.ts` crea incidentes, devuelve recibos idempotentes y valida transiciones. `conversations.ts` persiste mensajes por secuencia y controla los autores según el estado operativo. `dispatch.ts` calcula la prioridad efectiva, registra correcciones y expone la auditoría. `incident-purge.ts` verifica la selección, crea un respaldo y conserva tombstones antes de eliminar los registros elegidos. La migración 008 aplica la unicidad de las asignaciones activas. [Modelo de datos](data-model.md)

`venue.ts` reutiliza eventos y zonas, restringe las mutaciones al organizador miembro y confirma la escritura, la revisión y la auditoría en una transacción. Las zonas y los puntos se desactivan conservando sus identificadores.

## Trabajadores de IA

El triaje histórico usa `triage-worker.ts` y `triage-provider.ts`. El chat usa una sola interpretación por turno elegible, coordinada por `chat-worker.ts` y `chat-analysis.ts`. Conserva el máximo de 20 llamadas por incidente y permite asignar un presupuesto diario propio. La recepción del SOS, el traspaso y el cierre se confirman en SQLite. [Turnos y reglas de prioridad](current-workflow.md#3-turnos-de-ia-prioridad-y-ubicación)

Al guardar el mensaje, `chat-risk.ts` registra observaciones locales y resuelve respuestas breves usando la pregunta asociada al mensaje inmediatamente anterior. `chat-response.ts` decide la orientación urgente y compone una respuesta con un acuse y como máximo una pregunta pertinente. La IA sugiere una pregunta y un tono por códigos. El servidor comprueba lo que ya se sabe, evita preguntas recientes y controla todo el texto visible. Un resultado inválido, un timeout o un proveedor desactivado producen una respuesta local. Una respuesta en vuelo conserva el análisis si el incidente sigue en `AUTO` y su versión sigue vigente. El traspaso invalida la respuesta automática.

El trabajador carga el catálogo activo como datos no confiables y limita la entrada estructurada a 4500 bytes. Fastify verifica las citas y compara una propuesta con el catálogo activo completo. La ubicación probable aparece en despacho desde que se acepta la evidencia. Una pregunta de ubicación se reserva para cuando aporta información y el riesgo lo permite. Una indicación explícita actualiza la procedencia. Las confirmaciones de preguntas antiguas de la migración 010 siguen vinculadas al mensaje que realmente las formuló.

En H7.3, el análisis y la respuesta dependían de la misma salida del proveedor. La variante inicial de H8.2 sustituyó el diálogo por una plantilla al recibir contexto de recinto y seleccionaba la ubicación del último análisis, aunque ese turno fuera un saludo. La recepción, la prioridad inmediata, la selección de ubicación y la respuesta ahora tienen decisiones independientes dentro del mismo flujo.

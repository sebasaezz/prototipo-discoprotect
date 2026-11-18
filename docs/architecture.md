# Arquitectura

## Integración H8.3–H10

Fastify conserva un modelo de autorización por sesión, rol y membresía para cada evento. GestiónWeb usa API REST con el Bearer en memoria. Los servicios de AppAsistente y AppPersonal reutilizan los clientes HTTP de cada app y descartan resultados de sesiones y eventos que dejaron de estar vigentes. Las apps mantienen NFC en su circuito de credenciales existente. El QR concede un único ingreso según el resultado de Fastify y usa una credencial aleatoria sin datos de identidad en su contenido.

Las estadísticas reutilizan la prioridad efectiva y obtienen totales y tiempos de las solicitudes del evento autorizado. Un cambio de guardia con atención activa transfiere la asignación sin cambiar su fase, incrementa la versión y registra el reemplazo. La sesión anterior queda invalidada. Los vínculos se forman por invitación consentida y su acceso de guardia se limita al incidente asignado y activo. Las campañas se filtran por evento y vigencia antes de enviarse al asistente.

El proceso local de chat persiste el relato, las señales de riesgo y la inferencia visual verificable en la transacción del turno. La evaluación remota está acotada y puede ampliar la clasificación, proponer preguntas o corroborar una zona. Fastify prepara las respuestas y aplica orientación fija derivada de protocolos públicos. El número de emergencia se obtiene de SAMU Chile. [Soporte vital básico para adultos, AHA](https://cpr.heart.org/en/resuscitation-science/cpr-and-ecc-guidelines/adult-basic-life-support/) · [Soporte vital pediátrico para primeros respondedores](https://cpr.heart.org/-/media/CPR-Files/CPR-Guidelines-Files/2025-Algorithms/Diagram-Ped-BLS-Lay-Rescuers-250121.pdf) · [SAMU 131](https://saludresponde.minsal.cl/fiestas-patrias-samu/)

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

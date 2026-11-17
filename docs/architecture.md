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

El triaje histórico usa `triage-worker.ts` y `triage-provider.ts`. El chat actual usa `chat-worker.ts` y `chat-analysis.ts`. Comparten ciclo de ejecución y cuota UTC en un proceso Fastify por SQLite. En `AUTO` se reserva un turno por mensaje elegible, con un máximo de 20 llamadas por incidente. Se valida la salida y la vigencia del incidente antes de publicarla. La recepción del SOS, el traspaso y el cierre se confirman por SQLite aunque el proveedor falle. [Turnos y reglas de prioridad](current-workflow.md#3-turnos-de-ia-prioridad-y-ubicación)

El trabajador del chat carga el contexto activo del evento del incidente al reservar cada turno. Filtra textos con los controles existentes y aplica un presupuesto total de 4500 bytes de entrada estructurada. Los textos del recinto entran como datos no confiables y las referencias inferidas requieren una cita del asistente y una correspondencia con el catálogo. Cuando hay contexto ampliado, el servidor publica solo citas para el resumen y redacta el mensaje visible con frases propias. AppPersonal muestra los puntos mediante la consulta operativa de Fastify. El triaje histórico mantiene su contrato.

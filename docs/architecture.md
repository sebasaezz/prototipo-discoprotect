# Arquitectura

El [flujo actual](current-workflow.md) describe la operación completa, el despliegue local y los permisos hasta H7.3. Esta página ubica las responsabilidades en el código.

| Componente | Responsabilidad |
| --- | --- |
| `protocol` | Construcción y análisis de APDU y sesión de tarjeta pura JVM. |
| `emitter` | HCE, identificación NFC, ficha y contactos propios, SOS de botón único, chat y cancelación. |
| `reader` | Reader Mode, IsoDep, resolución autorizada de fichas, cola, atención activa, chat y cierre. |
| `android-ui` | Componentes Compose compartidos para incidentes, chat y despacho. |
| `backend` | REST, permisos, operaciones y auditoría sobre SQLite, y trabajadores de IA. |
| `web` | Cola, historial, resumen y auditoría, retiro administrativo y purga demo mediante React, TypeScript y Vite. |

## Clientes y fronteras

`AttendeeController` y `ReaderController` coordinan sesión, evento, red y acciones. `HttpAttendeeApi` y `HttpStaffApi` usan OkHttp cancelable y Kotlin Serialization. Las apps conservan en memoria el Bearer y el estado pendiente. La web mantiene el Bearer en estado React y usa el proxy de Vite. Las lecturas se invalidan al cambiar el contexto.

`PersonCardService` atiende APDU desde el registro atómico `HceIdentityStore`. `CardSession` vincula SELECT y GET a la misma generación. `NfcReader` cierra IsoDep antes de entregar el ID al lector para su resolución por HTTP. `ProfileRepository` distingue la fuente remota de la demo local explícita. [Contrato NFC](nfc-contract.md)

## Fastify y SQLite

`main.ts` abre SQLite, aplica migraciones e inicia Fastify y los trabajadores. `app.ts` agrupa rutas y validación Zod. `auth.ts` verifica sesiones, roles y membresías. `records.ts` opera sobre cuentas, fichas, contactos y eventos. `db.ts` controla transacciones y migraciones.

`incidents.ts` crea incidentes, devuelve recibos idempotentes y valida transiciones. `conversations.ts` persiste mensajes por secuencia y controla los autores según el estado operativo. `dispatch.ts` calcula la prioridad efectiva, registra correcciones y expone la auditoría. `incident-purge.ts` verifica la selección, crea un respaldo y conserva tombstones antes de eliminar los registros elegidos. La migración 008 aplica la unicidad de las asignaciones activas. [Modelo de datos](data-model.md)

## Trabajadores de IA

El triaje histórico usa `triage-worker.ts` y `triage-provider.ts`. El chat actual usa `chat-worker.ts` y `chat-analysis.ts`. Comparten ciclo de ejecución y cuota UTC en un proceso Fastify por SQLite. En `AUTO` se reserva un turno por mensaje elegible, con un máximo de 20 llamadas por incidente. Se valida la salida y la vigencia del incidente antes de publicarla. La recepción del SOS, el traspaso y el cierre se confirman por SQLite aunque el proveedor falle. [Turnos y reglas de prioridad](current-workflow.md#3-turnos-de-ia-prioridad-y-ubicación)

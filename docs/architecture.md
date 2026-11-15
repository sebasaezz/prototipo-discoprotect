# Arquitectura

## H0–H1

| Componente | Responsabilidad |
| --- | --- |
| `protocol` | Construcción y análisis de APDU y sesión de tarjeta pura JVM. |
| `emitter` | Servicio HCE independiente, identificación NFC y edición de ficha y contactos de AppAsistente. |
| `reader` | Reader Mode, IsoDep, conexión al servidor y presentación de fichas de AppPersonal. |
| `android-ui` | Fuentes Compose compartidas por ambas apps. Mantiene los tres módulos Gradle originales. |
| `backend` | API REST Fastify, validación Zod, sesiones ficticias, migraciones y SQLite. |

AppAsistente transmite el ID por NFC. AppPersonal cierra IsoDep antes de resolverlo. El backend y SQLite son la fuente autoritativa para el modo conectado.

En el backend `main.ts` abre la base y arranca el proceso. `app.ts` define las rutas. `auth.ts` controla la sesión, el rol y la pertenencia. `records.ts` valida y opera sobre los datos. `db.ts` ejecuta transacciones y migraciones. La CLI permite inicializar y restaurar la demo.

## H2

`MainActivity` activa Reader Mode al estar en primer plano y ejecuta `NfcReader` en un ejecutor dedicado. `NfcReader` devuelve el ID validado o el fallo NFC y conserva los diagnósticos. El cierre de IsoDep ocurre antes de devolver el resultado.

`ReaderViewModel` conserva la sesión en memoria y persiste solo la URL. `ReaderController` coordina conectividad, autenticación, eventos y lecturas; la cancelación y la generación impiden que una respuesta tardía cambie la ficha. `ProfileRepository` resuelve explícitamente la fuente local o remota. `HttpStaffApi` usa OkHttp cancelable y Kotlin Serialization para validar el contrato de H1. Las respuestas 401, 403 y 404 mantienen sus significados y no disparan una consulta local.

`ConnectionSetup` presenta la conexión, el PIN y los eventos autorizados. `ReaderScreen` distingue el intercambio NFC de la consulta al servidor y presenta el origen de la ficha. La demo local conserva los perfiles ficticios de H1.

## H3

`HttpAttendeeApi` usa el patrón OkHttp y Kotlin Serialization de H2, con contratos propios del asistente. Antes de entregar la sesión comprueba el rol, la cuenta `antonia` y la coincidencia entre la credencial activa del servidor y el ID que ya emite HCE. El token vive en memoria y se persiste solo el origen del servidor. `EmergencyRepository` comprueba la versión de la ficha antes y después de consultar contactos para obtener una vista consistente.

`AttendeeViewModel` y `AttendeeController` coordinan la sesión, la navegación y las escrituras. Los borradores viven en el ViewModel durante la rotación, separados de los datos confirmados. Las escrituras usan la versión del borrador. Ante 409 se carga el estado vigente y el usuario decide si descarta o vuelve a editar con esa versión. Tras un resultado incierto, se bloquea la escritura hasta consultar el estado del servidor. Las operaciones previas al cierre de sesión o a un cambio de origen se cancelan y sus resultados se descartan.

`AttendeeScreen` organiza Identificación, Mi ficha, Contactos y Conexión. `EditScreen` presenta los formularios y la revisión del conflicto. El servicio HCE sigue atendiendo APDU independientemente de la Activity, la sesión y la red. H4 podrá reutilizar la sesión y la verificación de credenciales cuando se diseñe la selección de cuentas.

## Extensiones previstas

GestiónWeb será un cliente React/TypeScript/Vite. El servidor persistirá cambios de incidentes antes de difundirlos por WebSocket, con resincronización posterior a desconexiones. La clasificación asistida por IA será un servicio desacoplado y el registro de SOS seguirá disponible si la clasificación falla. El [modelo de datos](data-model.md) recoge las entidades futuras para ubicaciones dentro del recinto.

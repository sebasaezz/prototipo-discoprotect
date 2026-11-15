# Arquitectura

## Componentes vigentes

| Componente | Responsabilidad |
| --- | --- |
| `protocol` | Construcción y análisis de APDU y sesión de tarjeta pura JVM. |
| `emitter` | Servicio HCE independiente, identificación NFC, edición de ficha y contactos y solicitudes SOS. |
| `reader` | Reader Mode, IsoDep, fichas y consulta de incidentes por evento. |
| `android-ui` | Fuentes Compose compartidas por ambas apps. Mantiene los tres módulos Gradle originales. |
| `backend` | API REST Fastify, validación Zod, sesiones ficticias, migraciones y SQLite. |
| `web` | Consulta de incidentes para el organizador en React, TypeScript y Vite. |

AppAsistente transmite por NFC el ID de la cuenta vigente verificada. AppPersonal cierra IsoDep antes de resolverlo. El backend y SQLite son la fuente autoritativa para el modo conectado.

En el backend `main.ts` abre la base y arranca el proceso. `app.ts` define las rutas. `auth.ts` controla la sesión, el rol y la pertenencia. `records.ts` valida y opera sobre los datos. `db.ts` ejecuta transacciones y migraciones. La CLI permite inicializar y restaurar la demo. El proyecto fija Fastify 5.12.8 y Zod 4.6.7, usa TypeScript ~5.9.3 y SQLite mediante `node:sqlite`. `package-lock.json` fija las dependencias instaladas por `npm ci`. Las dos apps usan OkHttp 4.12.0 y Kotlin Serialization JSON 1.9.0. El [README](../README.md) especifica la toolchain Android y los comandos de arranque.

## H2

`MainActivity` activa Reader Mode al estar en primer plano y ejecuta `NfcReader` en un ejecutor dedicado. `NfcReader` devuelve el ID validado o el fallo NFC y conserva los diagnósticos. El cierre de IsoDep ocurre antes de devolver el resultado.

`ReaderViewModel` conserva la sesión en memoria y persiste solo la URL. `ReaderController` coordina conectividad, autenticación, eventos y lecturas; la cancelación y la generación impiden que una respuesta tardía cambie la ficha. `ProfileRepository` resuelve explícitamente la fuente local o remota. `HttpStaffApi` usa OkHttp cancelable y Kotlin Serialization para validar el contrato de H1. Las respuestas 401, 403 y 404 mantienen sus significados y no disparan una consulta local.

`ConnectionSetup` presenta la conexión, el PIN y los eventos autorizados. `ReaderScreen` distingue el intercambio NFC de la consulta al servidor y presenta el origen de la ficha. La demo local conserva los perfiles ficticios de H1.

## H3

`HttpAttendeeApi` usa el patrón OkHttp y Kotlin Serialization de H2, con contratos propios del asistente. En H3, la sesión estaba limitada a `antonia` y comprobaba la coincidencia con el ID fijo. El token vive en memoria y se persiste solo el origen del servidor. `EmergencyRepository` comprueba la versión de la ficha antes y después de consultar contactos para obtener una vista consistente.

`AttendeeViewModel` y `AttendeeController` coordinan la sesión, la navegación y las escrituras. Los borradores viven en el ViewModel durante la rotación, separados de los datos confirmados. Las escrituras usan la versión del borrador. Ante 409 se carga el estado vigente y el usuario decide si descarta o vuelve a editar con esa versión. Tras un resultado incierto, se bloquea la escritura hasta consultar el estado del servidor. Las operaciones previas al cierre de sesión o a un cambio de origen se cancelan y sus resultados se descartan.

`AttendeeScreen` organiza Identificación, Mi ficha, Contactos y Conexión. `EditScreen` presenta los formularios y la revisión del conflicto. El servicio HCE sigue atendiendo APDU independientemente de la Activity y de la red.

## H4

`HttpAttendeeApi` acepta una de las tres cuentas demo, verifica el rol y la identidad de retorno, y convierte la credencial activa a 16 bytes. `AttendeeController` invalida primero el registro HCE al confirmar un cambio y cancela las operaciones obsoletas. La ficha, los contactos y las escrituras versionadas quedan ligados al Bearer verificado de esa cuenta.

`HceIdentityStore` contiene un único estado atómico. `PersonCardService` usa la misma fuente que la UI y confirma la vigencia en cada APDU. `CardSession` vincula SELECT y GET a la misma generación. El registro y el Bearer desaparecen con el proceso. El [guion H4](h4-integration.md) detalla expiración, política offline y pruebas. El [handoff](h4-handoff.md) conserva el análisis anterior a la implementación.

## H5

`incidents.ts` encapsula la escritura transaccional y las consultas. La API deriva la identidad de la sesión y exige el rol y la membresía del evento. AppAsistente usa el Bearer de H4 y el control de generación existente. AppPersonal consulta incidentes con la sesión de guardia y el evento activo. Ambos clientes ignoran respuestas de consultas invalidadas por cambios de sesión o contexto.

La web solicita una sesión de organizador con PIN y conserva el Bearer en estado React. El proxy local de Vite permite usar rutas del mismo origen en el navegador. AppPersonal y la web obtienen la misma vista operativa de la API y permiten actualización manual. [Guion H5](h5-integration.md)

## Extensiones previstas

H6 contempla sincronización de cambios persistidos y recuperación tras desconexiones. La clasificación asistida por IA de H7 será un servicio desacoplado. La gestión administrativa se incorpora en H8. El [modelo de datos](data-model.md) recoge las entidades futuras para ubicaciones dentro del recinto.

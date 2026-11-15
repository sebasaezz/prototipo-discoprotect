# Arquitectura

## H0–H1

| Componente | Responsabilidad |
| --- | --- |
| `protocol` | Construcción y análisis de APDU y sesión de tarjeta pura JVM. |
| `emitter` | Servicio HCE y pantalla de identificación de AppAsistente. |
| `reader` | Reader Mode, IsoDep, conexión al servidor y presentación de fichas de AppPersonal. |
| `android-ui` | Fuentes Compose compartidas por ambas apps. Mantiene los tres módulos Gradle originales. |
| `backend` | API REST Fastify, validación Zod, sesiones ficticias, migraciones y SQLite. |

AppAsistente transmite el ID por NFC. AppPersonal cierra IsoDep antes de resolverlo. El backend y SQLite son la fuente autoritativa para el modo conectado.

En el backend `main.ts` abre la base y arranca el proceso. `app.ts` define las rutas. `auth.ts` controla la sesión, el rol y la pertenencia. `records.ts` valida y opera sobre los datos. `db.ts` ejecuta transacciones y migraciones. La CLI permite inicializar y restaurar la demo.

## H2

`MainActivity` activa Reader Mode al estar en primer plano y ejecuta `NfcReader` en un ejecutor dedicado. `NfcReader` devuelve el ID validado o el fallo NFC y conserva los diagnósticos. El cierre de IsoDep ocurre antes de devolver el resultado.

`ReaderViewModel` conserva la sesión en memoria y persiste solo la URL. `ReaderController` coordina conectividad, autenticación, eventos y lecturas; la cancelación y la generación impiden que una respuesta tardía cambie la ficha. `ProfileRepository` resuelve explícitamente la fuente local o remota. `HttpStaffApi` usa OkHttp cancelable y Kotlin Serialization para validar el contrato de H1. Las respuestas 401, 403 y 404 mantienen sus significados y no disparan una consulta local.

`ConnectionSetup` presenta la conexión, el PIN y los eventos autorizados. `ReaderScreen` distingue el intercambio NFC de la consulta al servidor y presenta el origen de la ficha. La demo local conserva los perfiles ficticios de H1.

H3 puede reutilizar la separación entre UI, transporte REST y modelos tipados para implementar la edición en AppAsistente sobre las rutas propias y el control de versión existente.

## Extensiones previstas

GestiónWeb será un cliente React/TypeScript/Vite. El servidor persistirá cambios de incidentes antes de difundirlos por WebSocket, con resincronización posterior a desconexiones. La clasificación asistida por IA será un servicio desacoplado y el registro de SOS seguirá disponible si la clasificación falla. El [modelo de datos](data-model.md) recoge las entidades futuras para ubicaciones dentro del recinto.

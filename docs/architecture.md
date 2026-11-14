# Arquitectura

## H0–H1

| Componente | Responsabilidad |
| --- | --- |
| `protocol` | Construcción y análisis de APDU y sesión de tarjeta pura JVM. |
| `emitter` | Servicio HCE y pantalla de identificación de AppAsistente. |
| `reader` | Reader Mode, IsoDep y resolución local de fichas de AppPersonal. |
| `android-ui` | Fuentes Compose compartidas por ambas apps. Mantiene los tres módulos Gradle originales. |
| `backend` | API REST Fastify, validación Zod, sesiones ficticias, migraciones y SQLite. |

En H1 AppAsistente transmite el ID por NFC. AppPersonal cierra IsoDep y obtiene la ficha de su repositorio local. La API y SQLite se ejecutan y verifican de forma independiente. Desde H2 el backend será la fuente autoritativa para la consulta conectada.

En el backend `main.ts` abre la base y arranca el proceso. `app.ts` define las rutas. `auth.ts` controla la sesión, el rol y la pertenencia. `records.ts` valida y opera sobre los datos. `db.ts` ejecuta transacciones y migraciones. La CLI permite inicializar y restaurar la demo.

## H2

Tras cerrar IsoDep, AppPersonal entregará el ID a un repositorio que resuelva por API fuera del hilo NFC. Seleccionará el evento de una membresía de guardia y usará la sesión demo para llamar a `POST /api/v1/nfc/resolve`. El resultado incluye identidad, ficha y contactos. El cliente deberá distinguir una ficha de servidor, un resultado local durante una desconexión y una credencial desconocida. Una denegación del servidor debe llegar a la interfaz como denegación.

## Extensiones previstas

GestiónWeb será un cliente React/TypeScript/Vite. El servidor persistirá cambios de incidentes antes de difundirlos por WebSocket, con resincronización posterior a desconexiones. La clasificación asistida por IA será un servicio desacoplado y el registro de SOS seguirá disponible si la clasificación falla. El [modelo de datos](data-model.md) recoge las entidades futuras para ubicaciones dentro del recinto.

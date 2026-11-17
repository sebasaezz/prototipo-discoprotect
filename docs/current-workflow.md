# Flujo actual de Discoprotect

Discoprotect es un prototipo universitario de gestión de incidentes en eventos masivos. H7–H7.3 se integraron a `main` en el merge `8f5439e` con 6/6 comprobaciones físicas del flujo principal aprobadas. H8.1 amplía GestiónWeb en `codex/discoprotect-h8`. [PR #7](https://github.com/sebasaezz/prototipo-discoprotect/pull/7)

## 1. Componentes y acceso a datos

| Componente | Función |
| --- | --- |
| AppAsistente, Galaxy S22 | Verifica una cuenta ficticia, mantiene su credencial NFC y permite editar su ficha y contactos. Crea solicitudes SOS, conversa, cancela solicitudes activas y consulta el historial propio. |
| AppPersonal, Galaxy S25 | Lee NFC y resuelve fichas con autorización. Consulta la cola, muestra **Mi atención activa**, abre el chat en una pantalla dedicada y permite asumir, iniciar y cerrar la atención. |
| GestiónWeb | Resume la operación del evento autorizado y muestra cola, historial, prioridades y auditoría para su organizador. Permite retirar solicitudes y, en modo demo habilitado, purgarlas. |
| Fastify y SQLite | Fastify valida contratos, sesiones y membresías. SQLite conserva cuentas, fichas, contactos, credenciales NFC, eventos, zonas, hashes de sesiones, incidentes, mensajes, clasificaciones, operaciones y auditoría. |
| OpenAI | Recibe contexto ficticio filtrado desde un trabajador de Fastify para interpretar mensajes y generar respuestas. Fastify conserva la autoridad sobre estados, permisos y prioridad efectiva. |

Los tres clientes usan REST `/api/v1` con Bearer en memoria. El navegador usa el proxy de Vite en desarrollo. El asistente consulta su ficha, sus contactos y sus incidentes. Un guardia miembro puede consultar los incidentes y chats del evento, incluido un chat aún sin asignar. Solo el guardia asignado puede escribir como personal o registrar correcciones operativas. El organizador miembro puede consultar el resumen y las transiciones en el evento. El hilo completo se sirve al titular y a guardias autorizados. El relato original del triaje histórico solo aparece en el detalle para personal. [Contrato API](api-contract.md)

## 2. Del SOS al cierre

1. En **Chat de ayuda**, el asistente elige el evento autorizado y activa **SOS**. Puede mantener el botón durante 1,5 segundos o usar su acción accesible y confirmar en el diálogo.
2. AppAsistente envía `mode: "SOS"` con un UUID generado por el cliente para ese envío. Fastify guarda el incidente `OPEN` y su conversación en una transacción. Devuelve el ID y la fecha de emisión sin esperar a OpenAI. El ID aparece en las próximas consultas de AppPersonal y GestiónWeb.
3. AppAsistente abre el chat del mismo incidente al confirmar el recibo. La conversación empieza vacía y muestra «Solicitud recibida. A la espera de información o atención del personal». Al pulsar **Enviar**, el texto se guarda como un mensaje del mismo chat. Un saludo como «hola» activa un turno de IA si el control sigue en `AUTO`.
4. Fastify encola cada mensaje elegible del asistente en `AUTO`. Tras analizarlo, puede actualizar la prioridad y la ubicación sugerida y publicar una respuesta visible, identificada como **Asistente IA**.
5. El guardia elige **Asumir conversación**. Una transacción asigna el incidente, registra el actor y cambia el control del chat a `STAFF`. La restricción de SQLite limita a una asignación activa por guardia entre todos los eventos. Otro intento de asumir devuelve `409 GUARD_ALREADY_BUSY`.
6. El guardia usa el mismo chat y registra con motivo la prioridad, la evaluación del peligro o la ubicación confirmada. **Iniciar atención** registra por separado el comienzo de la atención. AppPersonal ofrece **Cerrar solicitud** con motivo y confirmación. La API conserva **resolver** para el flujo H6.
7. El asistente puede cancelar incluso después de la asignación. El organizador puede retirar administrativamente una solicitud activa con motivo. Una acción terminal cierra el chat, libera la asignación activa y mueve la solicitud al historial con sus mensajes y auditoría.

```mermaid
sequenceDiagram
    participant S22 as AppAsistente S22
    participant API as Fastify y SQLite
    participant IA as OpenAI
    participant S25 as AppPersonal S25
    participant Web as GestiónWeb
    S22->>API: SOS sin texto
    API-->>S22: Recibo OPEN y chat vacío
    S25->>API: Consultar cola
    Web->>API: Consultar cola
    S22->>API: Enviar mensaje del chat
    API-->>S22: Confirmar mensaje
    API->>IA: Procesar turno AUTO
    IA-->>API: Interpretación y respuesta
    API-->>S22: Respuesta visible en la siguiente consulta
    S25->>API: Asumir conversación
    Note over API,IA: El traspaso descarta resultados tardíos
    S25->>API: Responder, registrar atención y cerrar
    API-->>S22: Estado terminal e historial
    API-->>Web: Estado terminal y auditoría
```

### Estados y actores

| Estado resultante | Transición autorizada | Control del chat |
| --- | --- | --- |
| `OPEN` | El asistente miembro crea la solicitud. | `AUTO` |
| `ASSIGNED` | Un guardia miembro y disponible asume un `OPEN`. | `STAFF` |
| `IN_PROGRESS` | El guardia asignado inicia la atención desde `ASSIGNED`. | `STAFF` |
| `RESOLVED` | El guardia asignado resuelve desde `IN_PROGRESS` por la ruta H6. | `CLOSED` |
| `CANCELLED` | El asistente titular cancela desde `OPEN`, `ASSIGNED` o `IN_PROGRESS`. | `CLOSED` |
| `CLOSED` | El guardia asignado cierra con motivo desde `ASSIGNED` o `IN_PROGRESS`. | `CLOSED` |
| `WITHDRAWN` | El organizador miembro retira con motivo desde cualquiera de los tres estados activos. | `CLOSED` |

El estado `CLOSED` del chat indica solo lectura para cualquier resultado terminal. La auditoría conserva el actor, la fecha y la transición. La desaparición de la cola activa se refleja en las siguientes consultas.

## 3. Turnos de IA, prioridad y ubicación

El trabajador reserva en SQLite un trabajo por secuencia del asistente y llama a Responses API fuera de la transacción. Procesa un trabajo de chat por vez y conserva los turnos en ráfaga. Envía contexto reciente filtrado, zonas candidatas y alias del evento. El modelo devuelve citas de mensajes, categoría, prioridad, ubicación, incertidumbre, resumen y una respuesta contextual de hasta 220 caracteres. Un turno elegible puede responder con un saludo, un reconocimiento o una pregunta breve.

El chat admite hasta 20 llamadas reservadas por incidente. El triaje histórico y el chat comparten la cuota de `TRIAGE_DAILY_LIMIT` por día UTC. Los trabajos en espera vencen según `TRIAGE_MAX_QUEUE_AGE_MS` y la llamada conversacional usa `CHAT_AI_TIMEOUT_MS`. Ante timeout, error, entrada filtrada, respuesta inválida o cuota agotada, Fastify conserva el incidente y registra el resultado del trabajo. AppAsistente muestra «IA temporalmente no disponible. Tu solicitud sigue visible para el personal» cuando el último turno en `AUTO` queda sin respuesta publicada. Los intentos reservados e interrumpidos pasan a atención manual al reiniciar. Un trabajo pendiente vigente puede continuar tras el reinicio.

Al confirmar una respuesta, Fastify vuelve a comprobar en SQLite que el incidente siga `OPEN` y que su versión operativa coincida con la reservada. Asumir o terminar el incidente invalida los trabajos pendientes y descarta el resultado que todavía esté en vuelo. Un mensaje nuevo del asistente antes del traspaso conserva su propio turno.

La **prioridad sugerida** procede del análisis. La **evidencia corroborada** exige una cita literal y una señal de peligro reconocida con comprobaciones locales de negación o resolución. La **decisión humana** se registra con motivo y revisión propia. Fastify calcula la prioridad efectiva:

- `P0` crítica, `P1` alta, `P2` media, `P3` baja y `PENDING` pendiente de evaluación. La cola ordena por `P0 → P1 → PENDING → P2 → P3`, luego por estado activo, antigüedad e ID.
- Una sugerencia `P0` con evidencia corroborada fija un piso crítico provisional. Una aclaración posterior de ubicación conserva ese piso. Un motivo histórico de peligro inmediato también fija un piso crítico. Una sugerencia crítica sin respaldo aporta `P1`.
- El guardia puede confirmar peligro (`CONFIRMED`) o registrar que fue descartado o superado (`CLEARED`). Retirar el piso permite aplicar la prioridad humana guardada o la sugerida vigente. La referencia a la evidencia queda en la auditoría.
- En `OPEN`, a los 120 s por defecto `P2` y `PENDING` pasan a `P1`, y `P3` pasa a `P2`. A los 300 s, `P3` pasa a `P1`. La escalada por espera termina al asignar y tiene techo `P1`.

La ubicación distingue lo declarado por el asistente, la zona inferida por IA y la corrección confirmada por el guardia. Los filtros y la corroboración son heurísticos. Usa datos ficticios y supervisión humana. La respuesta conversacional debe limitarse a orientación del diálogo y hechos declarados, evitando diagnósticos, tratamientos o promesas de llegada del personal.

## 4. Identificación NFC

AppAsistente verifica la cuenta, la sesión y la credencial activa con Fastify. El servicio HCE toma el ID opaco vigente de 16 bytes de un registro en memoria e intercambia por NFC el ID y los códigos de estado. La selección y la lectura deben corresponder a la misma generación. Cambiar de cuenta u origen, cerrar sesión o vencerla invalida la emisión anterior.

En el lector del S25, Reader Mode usa IsoDep para hacer SELECT y GET. AppPersonal cierra IsoDep antes de consultar `POST /api/v1/nfc/resolve`. Fastify comprueba la sesión del guardia y que ambas personas pertenezcan al evento autorizado antes de entregar ficha y contactos. La demo local requiere activación explícita y una nueva lectura. NFC identifica a la persona y el Bearer autoriza la consulta. El SOS y el chat se gestionan por HTTP y funcionan sin escaneo. [Contrato APDU y comprobaciones NFC](nfc-contract.md)

## 5. Administración de incidentes

### GestiónWeb H8.1

Al iniciar sesión con el PIN de organizador, GestiónWeb abre el **Dashboard** del evento autorizado. Con varias membresías de organizador, la cuenta selecciona uno de sus eventos. La barra lateral se convierte en un menú en pantallas pequeñas. El encabezado muestra nombre, recinto y estado del evento.

El Dashboard cuenta solicitudes **Activas**, **Sin asignar**, **Asignadas** y **En atención**. Hasta cinco solicitudes activas siguen el orden de despacho que entrega Fastify. El resumen comparte la consulta con **Incidentes** e indica la hora de la última respuesta correcta. Si falla la conexión, los datos disponibles se marcan como la última consulta. Si el servidor rechaza el acceso al evento, la vista descarta sus solicitudes.

**Recinto y zonas**, **Personal**, **Estadísticas** y **Anuncios y promociones** se muestran en desarrollo. Las seis secciones usan el mismo contexto de sesión y evento. Al cambiar de contexto se cancelan las lecturas y se limpia el estado local de los comandos. Cambiar de sección dentro del mismo evento permite volver a una confirmación o reintentar una operación incierta con el mismo comando.

### Cola, historial y acciones

En **Incidentes**, GestiónWeb separa **Cola activa** e **Historial**. **Retirar de la cola** exige un motivo y conserva el incidente con estado `WITHDRAWN`. Cancelar corresponde al asistente titular. Cerrar corresponde al guardia asignado.

En el evento ficticio `demo-festival`, **Eliminar definitivamente** selecciona un incidente y **Limpiar solicitudes demo** selecciona todos los incidentes del evento, incluidos los activos. La vista previa muestra la cantidad y devuelve una huella de la selección. El organizador confirma con `ELIMINAR`. Fastify crea un respaldo completo junto a la base en `demo-purge-backups/`, verifica integridad, claves foráneas y huella de la selección, y calcula su SHA-256, y aborta si la base cambió o falla el respaldo. La purga borra el incidente y sus dependencias en una transacción. Conserva cuentas, eventos, zonas, credenciales NFC y una auditoría separada de purga. Los tombstones ligan la cuenta y el UUID de cliente con la operación de purga. Un reenvío antiguo recibe `409 REQUEST_PURGED`. [Contrato de administración](api-contract.md#administración-y-purga)

## 6. Persistencia y sincronización

Fastify usa transacciones inmediatas para escrituras. El UUID de creación se deduplica por asistente y el UUID de mensaje por conversación. Las operaciones de personal y organizador usan versión esperada y UUID de operación. Un reenvío equivalente devuelve el resultado registrado y uno incompatible produce un conflicto. Las revisiones de ficha, triaje y despacho mantienen sus propias versiones.

Los listados visibles consultan aproximadamente cada cinco segundos y el chat abierto cada dos segundos. Ante fallos se espacian las lecturas hasta 30 segundos. El chat usa cursor por secuencia y reconcilia mensajes por ID y secuencia. Al recuperar la conexión, los clientes consultan la vista vigente. Las apps mantienen en memoria la clave y el cuerpo de un envío incierto para repetirlo; los registros ya confirmados se recuperan de SQLite al reiniciar. Un chat terminal conserva mensajes y rechaza escrituras nuevas. Un reenvío de un mensaje ya confirmado puede recuperar su recibo.

## 7. Despliegue local

Usa Node.js **22.16.0 o posterior** y npm. El manifiesto del backend declara 22.13.0, pero la ruta de purga habilitada invoca `node:sqlite.DatabaseSync.location()`, disponible desde 22.16.0. Para Android usa JDK 21, SDK Android 36 y el Gradle Wrapper 8.13. Configura `ANDROID_HOME` o `sdk.dir` en un `local.properties` local.

Desde `backend/`, copia `.env.example` a `.env` si todavía no existe. Elige una ruta SQLite nueva, por ejemplo `DATABASE_PATH=data/aceptacion-h73.sqlite`, y define PIN privados distintos para `DEMO_STAFF_PIN`, `DEMO_STAFF_2_PIN` y `DEMO_ORGANIZER_PIN`. Las cuentas privilegiadas correspondientes quedan deshabilitadas sin PIN. Desde esa carpeta:

```sh
npm ci
npm run db:seed
npm start
```

`db:seed` compila, aplica las migraciones 001–008 y agrega la demo. Arrancar Fastify también aplica las migraciones pendientes. Sobre una base anterior, trabaja primero en una copia consistente con Fastify detenido. La 008 detiene el arranque si un mismo guardia tiene varias asignaciones activas. Conserva los registros originales y requiere regularizarlas antes de reintentar. [Migraciones y persistencia](data-model.md)

En otra terminal, desde `web/`, ejecuta `npm ci` y `npm run dev`. Abre `http://127.0.0.1:5173`. Vite reenvía `/api/v1` al backend `http://127.0.0.1:3000`. Ajusta `DISCOPROTECT_API_ORIGIN` en el proceso de Vite si cambias el origen de Fastify.

Desde la raíz, ejecuta `./gradlew.bat :emitter:assembleDebug :reader:assembleDebug` en Windows o `./gradlew :emitter:assembleDebug :reader:assembleDebug` en Linux o macOS. Instala los APK debug en S22 y S25. Consulta sus rutas en el [README](../README.md#instalación-rápida). Selecciona `antonia`, `martin` o `sofia` en AppAsistente, `guardia` o `guardia2` en AppPersonal y usa el PIN de `organizador` en GestiónWeb. Selecciona el mismo evento.

| Configuración | Uso |
| --- | --- |
| `HOST`, `PORT`, `DATABASE_PATH` | Por defecto `127.0.0.1:3000` y `data/demo.sqlite` desde `backend/`. Para los teléfonos en la LAN controlada usa `HOST=0.0.0.0`, autoriza el puerto en la red privada y configura en ambos la URL con la IP LAN del PC. HTTP queda reservado a debug y datos ficticios. |
| `TRIAGE_ENABLED`, `OPENAI_API_KEY` | IA desactivada por defecto. Para activarla en una demo ficticia usa `true` y una clave local. Desactívala con `false` y reinicia Fastify para verificar el flujo manual. |
| `OPENAI_CHAT_MODEL`, `OPENAI_MODEL` | Por defecto `gpt-6.1-sol` en el chat y `gpt-5.4-mini` en triaje histórico. El adaptador conversacional envía esfuerzo `low`. |
| `TRIAGE_DAILY_LIMIT`, `TRIAGE_MAX_QUEUE_AGE_MS`, `TRIAGE_TIMEOUT_MS`, `CHAT_AI_TIMEOUT_MS`, `CHAT_AI_MAX_ZONES` | Valores iniciales de 100 llamadas UTC, espera máxima de 30000 ms, timeout H7 de 5000 ms, timeout del chat de 15000 ms y catálogo conversacional de hasta 16 zonas. |
| `DISPATCH_EARLY_SECONDS`, `DISPATCH_LATE_SECONDS` | Umbrales de despacho iniciales de 120 y 300 segundos. |
| `DEMO_PURGE_ENABLED`, `ALLOW_DEMO_RESET` | Ambas opciones empiezan en `false`. Habilita la purga solo para una base descartable y vuelve a desactivarla tras verificarla. El reset es una operación independiente de CLI que restaura las entidades de la demo. |

Para validar el entorno, consulta `GET /api/v1/health`. Continúa con el [guion físico vigente](h7-2-integration.md), el [checkpoint H7.3](checkpoints/h7-3-2026-11-17.md) y el [alcance de H8](roadmap.md#h8--administración-de-eventos-y-personal).

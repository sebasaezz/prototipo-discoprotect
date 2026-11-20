# Discoprotect

Prototipo universitario de seguridad para eventos masivos. AppAsistente gestiona ficha, NFC, chat SOS, entrada QR, acompañantes y promociones. AppPersonal usa NFC, atiende y localiza incidentes, consulta el recinto y valida entradas. GestiónWeb administra la operación, el recinto, los guardias y los servicios de cada evento.

**Rama de revisión.** `codex/discoprotect-final-h10` integra H8.2 con el PR #8 y añade H8.3–H10. La evidencia de la rama y la aceptación física histórica están en [aceptación final](docs/checkpoints/final-acceptance.md) y [checkpoint H7.3](docs/checkpoints/h7-3-2026-11-17.md).

## Instalación rápida

Usa Node.js **22.16.0 o posterior**, npm, JDK 21 y SDK Android 36. Configura `ANDROID_HOME` o `sdk.dir` en un `local.properties` local. Android usa Gradle Wrapper 8.13 y requiere API 26 o posterior en los teléfonos.

Para una demo nueva en Windows, instala las dependencias de cada módulo con `npm ci`, configura los PIN en `backend/.env` y ejecuta `./scripts/demo-final.ps1` desde la raíz. El script crea una base nueva y levanta Fastify. Usa `-Lan` para teléfonos en la red privada y `-EnableAI` para habilitar el chat remoto. El [guion final](docs/demo-final.md) incluye instalación, roles, datos semilla y recuperación. `./scripts/validate-final.ps1` ejecuta las suites y genera el build web y ambos APK.

### Backend

En `backend/`, copia `.env.example` a `.env` si todavía no existe. Define PIN locales distintos para `DEMO_STAFF_PIN`, `DEMO_STAFF_2_PIN` y `DEMO_ORGANIZER_PIN`. Para la primera demo usa una ruta SQLite nueva, por ejemplo `DATABASE_PATH=data/aceptacion-h73.sqlite`. Desde esa carpeta ejecuta:

```sh
npm ci
npm run db:seed
npm start
```

`db:seed` compila, aplica las migraciones y crea las cuentas y el evento ficticios. Comprueba `http://127.0.0.1:3000/api/v1/health`. Antes de actualizar una base previa, consulta la [migración 008 y la preparación local](docs/current-workflow.md#7-despliegue-local).

### GestiónWeb

En otra terminal, desde `web/`, ejecuta `npm ci` y `npm run dev`. Abre `http://127.0.0.1:5173` e ingresa el PIN del organizador. Si cambiaste el puerto u origen de Fastify, define `DISCOPROTECT_API_ORIGIN` para Vite.

El Dashboard usa las solicitudes del evento devueltas por Fastify y muestra cero cuando la cola activa está vacía. En **Incidentes** están la cola, el historial y las acciones de H7.3. [GestiónWeb H8.1](docs/current-workflow.md#gestiónweb-h81)

En **Recinto y zonas** puedes editar el recinto, gestionar zonas y registrar puntos operativos. **Eventos**, **Personal**, **Estadísticas**, **Anuncios y promociones**, **Entradas** y **Búsqueda** operan sobre el evento seleccionado. [Flujo de trabajo](docs/current-workflow.md)

### Android

Para una LAN de desarrollo controlada, configura `HOST=0.0.0.0` en el backend y permite el puerto en el firewall de red privada. Desde la raíz compila ambos APK con `./gradlew.bat :emitter:assembleDebug :reader:assembleDebug` en Windows o `./gradlew :emitter:assembleDebug :reader:assembleDebug` en Linux o macOS. Usa `adb devices -l` y `adb -s <SERIAL> install -r <APK>` para instalarlos. En ambas apps configura `http://<IP-LAN-PC>:3000` y selecciona el mismo evento.

| Aplicación | Equipo previsto | APK debug |
| --- | --- | --- |
| AppAsistente | Galaxy S22 | `emitter/build/outputs/apk/debug/emitter-debug.apk` |
| AppPersonal | Galaxy S25 | `reader/build/outputs/apk/debug/reader-debug.apk` |

La demo arranca con IA y purga desactivadas. El [flujo actual](docs/current-workflow.md) explica las cuentas, la activación segura de IA y purga, los estados de atención y el recorrido completo del SOS.

## Continuidad

- [Guion físico vigente](docs/h7-2-integration.md) y [checkpoint H7.3](docs/checkpoints/h7-3-2026-11-17.md).
- [Instrucciones para agentes](AGENTS.md) y [alcance de H8](docs/roadmap.md#h8--administración-de-eventos-y-personal).
- [Arquitectura](docs/architecture.md), [contrato API](docs/api-contract.md), [modelo de datos](docs/data-model.md), [contrato NFC](docs/nfc-contract.md) y [Design System](docs/design-system.md).

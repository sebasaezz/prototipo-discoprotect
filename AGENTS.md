# Discoprotect

Prototipo universitario de seguridad para eventos masivos. AppAsistente y AppPersonal usan Kotlin, Jetpack Compose y Material 3. El servidor central es un monolito Fastify/TypeScript con SQLite. GestiónWeb consumirá la misma API; su implementación figura en el [roadmap](docs/roadmap.md).

## Alcance y método

- Implementa solo los hitos autorizados explícitamente. Usa el roadmap para conocer las dependencias.
- Trabaja por funciones verificables. Inspecciona, implementa, prueba y documenta cada avance.
- Lee [arquitectura](docs/architecture.md), [contrato API](docs/api-contract.md), [modelo de datos](docs/data-model.md) y [Design System](docs/design-system.md) antes de modificar sus áreas.
- Usa datos ficticios y configuración por entorno. Excluye credenciales y fichas de los logs. Mantén documentación y pruebas sincronizadas con los contratos.
- H1 usa perfiles locales en Android. La lectura HTTP del backend se incorpora en H2.

## NFC

- Preserva HCE, Reader Mode, IsoDep, AID, APDU y pruebas existentes. El [contrato NFC](docs/nfc-contract.md) detalla los bytes y la verificación física.
- La respuesta NFC contiene únicamente el identificador opaco y el código de estado. HCE funciona sin depender de la Activity.
- Evita red o bloqueos en `processCommandApdu()`. Cierra IsoDep antes de resolver la ficha. Conserva los diagnósticos y la protección frente a resultados obsoletos al salir de Reader Mode.
- Ejecuta `./gradlew :protocol:test :emitter:assembleDebug :reader:assembleDebug :emitter:lintDebug :reader:lintDebug` para cambios Android y `npm test` desde `backend/` para cambios de API o datos. Registra por separado las verificaciones automáticas, visuales y físicas.

## Diseño y Git

- Usa el tema oficial claro, con fondo blanco, morado #402078, lavanda #AE7EDB y texto #241735. Añade estados semánticos con texto y conserva objetivos táctiles de al menos 48 dp.
- Trabaja en una rama de desarrollo. Preserva cambios ajenos, agrupa commits coherentes, revisa `git diff` y `git status` y mantén la integración a main como paso de revisión.

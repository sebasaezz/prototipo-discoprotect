# Discoprotect

Prototipo universitario para mejorar la seguridad y la respuesta ante incidentes en eventos masivos. AppAsistente (`:emitter`) sirve a asistentes. AppPersonal (`:reader`) sirve al personal de seguridad. `:protocol` define el intercambio NFC y `android-ui/` comparte el tema Compose. `backend/` centraliza la API y la persistencia. GestiónWeb para organizadores está pendiente.

## Retomar el proyecto

- H0 está implementado y validado en hardware. H1 está implementado. H2 y H3 están implementados y validados físicamente por el usuario. H3 se validó el 15 de noviembre de 2026. H4 se implementó y validó físicamente el 15 de noviembre de 2026. H5 es el siguiente hito. H5–H10 están pendientes.
- Empieza por el [checkpoint H4](docs/checkpoints/h4-2026-11-15.md) y el [roadmap](docs/roadmap.md). El [checkpoint H3](docs/checkpoints/h3-2026-11-15.md) conserva la evidencia anterior. El [guion H4](docs/h4-integration.md) explica cómo validar las cuentas dinámicas.
- Consulta la [arquitectura](docs/architecture.md), el [contrato API](docs/api-contract.md), el [modelo de datos](docs/data-model.md) y el [contrato NFC](docs/nfc-contract.md) antes de modificar esas áreas. Usa el [README](README.md) para ejecutar el proyecto y el [checkpoint](docs/checkpoints/h3-2026-11-15.md#pruebas-automatizadas) para reproducir las pruebas. Separa resultados automatizados, visuales y físicos.
- Para continuar, verifica la revisión y el SHA de la base remota. Usa una rama de trabajo que contenga los hitos integrados, preserva cambios ajenos, revisa `git diff` y `git status`, y conserva la fusión como paso de revisión.

## Reglas de implementación

- Trabaja por funciones verificables dentro del hito autorizado. Mantén contratos, documentación y pruebas sincronizados.
- Preserva AID, APDU, HCE, Reader Mode, IsoDep y sus pruebas. Cambiar el AID requiere autorización. Envía solo el identificador opaco y el código de estado por NFC. Mantén `HostApduService` independiente de la Activity y de HTTP, y evita operaciones que bloqueen su callback.
- Cierra IsoDep antes de consultar el backend en AppPersonal. Mantén diagnósticos y protección contra respuestas obsoletas. La demo local requiere activación explícita y una nueva lectura.
- H4 habilita la emisión solo después de verificar cuenta, sesión y credencial del servidor. El registro activo y el Bearer viven en memoria. Invalida al cambiar cuenta u origen, cerrar o vencer sesión. Evita cualquier respaldo a un ID fijo. SELECT y GET deben compartir la misma generación vigente.
- La ficha y los contactos son de la cuenta activa. Descarta los borradores al confirmar otro perfil. Conserva las escrituras versionadas y la revisión explícita ante 409 o resultado incierto.
- Usa datos ficticios y configuración por entorno. Excluye secretos, PIN privados, tokens Bearer, fichas reales y bases locales de Git y de los logs. Mantén PIN y Bearer fuera del almacenamiento persistente de las apps. HTTP sin TLS se reserva a debug en una LAN controlada.
- Respeta el [Design System](docs/design-system.md), con tema claro, morado #402078, lavanda #AE7EDB y texto #241735. Usa estados con etiquetas y objetivos táctiles de al menos 48 dp.

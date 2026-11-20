# Discoprotect

Prototipo universitario para mejorar la seguridad y la respuesta ante incidentes en eventos masivos. AppAsistente (`:emitter`) sirve a asistentes. AppPersonal (`:reader`) sirve al personal de seguridad. `:protocol` define el intercambio NFC y `android-ui/` comparte componentes Compose. `backend/` centraliza la API y la persistencia. `web/` permite al organizador supervisar y administrar solicitudes del evento.

## Retomar el proyecto

- H0 y H2–H6 cuentan con validaciones físicas previas según sus checkpoints. H1 está implementado. H6 se integró a `main` tras 26/26 comprobaciones. H7–H7.3 se integraron por el [PR #7](https://github.com/sebasaezz/prototipo-discoprotect/pull/7) con 6/6 comprobaciones físicas. H8.1 se integró por el [PR #8](https://github.com/sebasaezz/prototipo-discoprotect/pull/8). Consulta la [aceptación final](docs/checkpoints/final-acceptance.md) y el [guion final](docs/demo-final.md) para el estado de la rama H10.
- Retoma desde el [flujo actual](docs/current-workflow.md), la [aceptación final](docs/checkpoints/final-acceptance.md) y el [guion vigente](docs/demo-final.md). Para instalar, usa el [README](README.md). Para continuar el desarrollo, verifica el SHA de la base remota y consulta el [roadmap](docs/roadmap.md). Conserva los checkpoints previos como evidencia histórica.
- Antes de cambiar una interfaz o regla, consulta la [arquitectura](docs/architecture.md), el [contrato API](docs/api-contract.md), el [modelo de datos](docs/data-model.md) y el [contrato NFC](docs/nfc-contract.md). Registra por separado las verificaciones automatizadas, visuales y físicas.
- Usa una rama que incluya los hitos integrados, preserva cambios ajenos y revisa `git diff` y `git status`. La fusión queda como paso de revisión.

## Reglas de implementación

- Trabaja por funciones verificables dentro del hito autorizado. Mantén contratos, documentación y pruebas sincronizados.
- Preserva AID, APDU, HCE, Reader Mode, IsoDep y sus pruebas. Cambiar el AID requiere autorización. Envía solo el identificador opaco y el código de estado por NFC. Mantén `HostApduService` independiente de la Activity y de HTTP, y evita operaciones que bloqueen su callback.
- Cierra IsoDep antes de consultar el backend en AppPersonal. Mantén diagnósticos y protección contra respuestas obsoletas. La demo local requiere activación explícita y una nueva lectura.
- H4 habilita la emisión solo después de verificar cuenta, sesión y credencial del servidor. El registro activo y el Bearer viven en memoria. Invalida al cambiar cuenta u origen, cerrar o vencer sesión. Evita cualquier respaldo a un ID fijo. SELECT y GET deben compartir la misma generación vigente.
- La ficha y los contactos son de la cuenta activa. Descarta los borradores al confirmar otro perfil. Conserva las escrituras versionadas y la revisión explícita ante 409 o resultado incierto.
- Asocia el SOS a la cuenta de sesión y a un evento autorizado. Reutiliza la clave y el cuerpo al reintentar un envío incierto. Confirma la recepción con el registro devuelto por el servidor. Conserva los incidentes al cancelar, cerrar o retirar. El chat pasa a `STAFF` al asumir, descarta las respuestas tardías de IA y conserva la asignación activa única por guardia. La purga demo queda desactivada por defecto y requiere confirmación y respaldo verificado.
- Usa datos ficticios y configuración por entorno. Excluye secretos, PIN privados, tokens Bearer, fichas reales y bases locales de Git y de los logs. Mantén PIN y Bearer fuera del almacenamiento persistente de las apps. HTTP sin TLS se reserva a debug en una LAN controlada.
- Respeta el [Design System](docs/design-system.md), con tema claro, morado #402078, lavanda #AE7EDB y texto #241735. Usa estados con etiquetas y objetivos táctiles de al menos 48 dp.
- Conserva el aislamiento por evento en todos los servicios H8–H9 y la independencia de QR, NFC y SOS. Valida las entradas en SQLite mediante operaciones idempotentes. El guardia asignado solo puede consultar los nombres de acompañantes consentidos durante una atención activa.
- Prueba las migraciones con bases desechables o copias verificadas. Para restaurar una demo, crea otra base con `scripts/demo-final.ps1`. Mantén las pruebas reales de IA opcionales, con ficción, presupuesto y registro de consumo.

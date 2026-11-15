# Handoff H4 · Cuentas y credenciales NFC dinámicas

Este documento conserva las alternativas evaluadas antes de implementar H4. La [decisión y matriz vigentes](h4-integration.md) describen la implementación, pendiente de validación física integrada. El [checkpoint](checkpoints/h3-2026-11-15.md) registra la evidencia y las referencias Git. El objetivo es seleccionar Antonia, Martín o Sofía y emitir solo la credencial activa que corresponde a la selección validada.

## Punto de partida en H3

- [NfcProtocol.kt](../protocol/src/main/kotlin/cl/innova/nfc/protocol/NfcProtocol.kt) define `DEMO_PERSON_ID` y `CardSession`. Cada instancia de [PersonCardService](../emitter/src/main/kotlin/cl/innova/nfc/emitter/PersonCardService.kt) crea su propia sesión RF. Un SELECT válido habilita GET, que devuelve siempre el ID fijo de Antonia. Un SELECT rechazado o `onDeactivated()` limpia la selección. Una instancia recreada, incluso en un proceso nuevo, empieza sin SELECT y puede emitir Antonia después de seleccionar el AID, al margen de la Activity y de la sesión HTTP. [Contrato y configuración HCE](nfc-contract.md)
- [HttpAttendeeApi](../emitter/src/main/kotlin/cl/innova/nfc/emitter/AttendeeApi.kt) autentica solo a `antonia` y comprueba rol `ATTENDEE`, cuenta en `GET /me` y credencial activa fija en `GET /me/nfc-credential`. El [ViewModel](../emitter/src/main/kotlin/cl/innova/nfc/emitter/AttendeeViewModel.kt) persiste solo el origen. El [controlador](../emitter/src/main/kotlin/cl/innova/nfc/emitter/AttendeeController.kt) conserva el Bearer en memoria, invalida trabajo obsoleto al cerrar sesión o cambiar de servidor y atiende caducidad local y 401. Un borrador que supera una reautenticación necesita el mismo `person.id`. El cierre es local, ya que H3 carece de un endpoint de revocación.
- El [seed](../backend/src/seed.ts) provisiona credenciales de 16 bytes para `antonia` (`8F31A20C779145D4B522F10492AA610E`), `martin` (`00000000000000000000000000000002`) y `sofia` (`00000000000000000000000000000003`). Son datos ficticios. Las tres cuentas son `ATTENDEE` de `demo-festival` y las credenciales del seed empiezan activas. El [contrato REST](api-contract.md) ya permite iniciar sesiones y consultar identidad y credencial propias. En H3 esa consulta verifica la cuenta fija. El servicio no consume su resultado.

## Invariantes de una transición

- Antes de anunciar «Listo para compartir», la identidad de UI y el estado que leerá HCE deben corresponder al mismo origen y persona verificados, con rol `ATTENDEE`, vigencia según la política elegida y credencial activa de 16 bytes.
- Bloquea emisión antes de cambiar cuenta, origen o sesión. Valida identidad y credencial nuevas y publica ambas juntas. Si hay interrupción, timeout, credencial ausente o inactiva, cuenta incorrecta o respuesta tardía, conserva un estado sin credencial válida hasta completar la verificación. Desasocia también fichas y borradores de la cuenta previa.
- Define qué pasa entre SELECT y GET si se inicia una transición. Una opción es fijar la generación al seleccionar el AID y rechazar GET si cambió, incluso si la cuenta volvió a ser la misma. Así se evita emitir el ID anterior o encadenar dos identidades en un toque.
- El cierre, la expiración y el arranque en frío deben quedar cubiertos por la política de publicación y limpieza. La pérdida de LAN debe distinguir una identidad ya verificada de una transición sin terminar. Define si se permite emitir un ID verificado mientras dure la sesión y cómo se invalida tras caducidad o cambios remotos. La demo local de AppPersonal conserva su selección explícita.
- El callback HCE debe seguir respondiendo a partir de un estado disponible localmente y sin bloquear su hilo. Preserva AID, formas de APDU y payload. Para un estado sin credencial, evalúa reutilizar un rechazo existente y verifica que AppPersonal lo muestre como lectura fallida sin consultar la API. Usa el S25 actual como prueba de compatibilidad. [Ciclo de vida y callbacks HCE en Android](https://developer.android.com/develop/connectivity/nfc/hce)

## Opciones por evaluar

| Opción | Ventaja | Riesgo o decisión |
| --- | --- | --- |
| Estado compartido en memoria del proceso, con publicación atómica y generación | El servicio accede al estado aunque la Activity esté cerrada. Una instancia del servicio en el mismo proceso puede leer el estado vigente. | La muerte del proceso elimina la identidad y obliga a revalidar. Verificar que servicio y UI compartan proceso y que la invalidación ocurra antes de cualquier transición. |
| Registro local duradero vinculado a origen, persona, expiración y generación | Puede recuperar una selección tras reinicio si la política lo permite. | Persistir un ID no prueba que su credencial siga activa. Debe definirse revalidación, limpieza atómica y comportamiento cuando HCE inicia antes que la UI. Evitar persistir el Bearer y leer disco dentro del callback. |
| Habilitar o deshabilitar el componente o el enrutamiento HCE | Puede apoyar la disponibilidad visible del servicio cuando no hay identidad. | El enrutamiento del sistema puede cambiar con latencia y no sustituye el control de identidad en GET. Verificar tiempos, reactivación y Secure NFC en Samsung. |

H4 eligió el registro en memoria, emisión offline solo hasta el vencimiento y rechazo `6A82` sin identidad. La aceptación exige que cualquier política elegida impida emitir la cuenta equivocada.

## Matriz de aceptación

| Escenario | Resultado a comprobar |
| --- | --- |
| Iniciar sesión como Antonia y escanear | El S25 consulta Antonia tras cerrar IsoDep. |
| Cambiar a Martín y escanear | La credencial de Martín abre su ficha y el ID de Antonia deja de emitirse. |
| Cambiar a Sofía y escanear | Se obtiene la ficha de Sofía y el ID de Martín deja de emitirse. |
| Escanear durante SELECT → GET y durante el cambio de cuenta | Una transición invalidada rechaza la lectura. El S25 no resuelve una identidad equivocada. |
| Cuenta sin credencial, inactiva o discrepante | La UI no anuncia disponibilidad y GET no devuelve un ID anterior. |
| Cerrar sesión o provocar expiración | Se limpia el estado compartible y el S25 no obtiene la cuenta anterior. |
| Recrear Activity y dejarla en segundo plano | El comportamiento corresponde al estado verificado según la política, sin dependencia de una pantalla activa. |
| Reiniciar el proceso, cuando sea reproducible | El servicio empieza sin identidad o con una identidad revalidada según la política. Nunca vuelve por defecto a Antonia. |
| Interrumpir cambio de cuenta o demorar una respuesta antigua | La operación obsoleta no publica sesión, ficha ni credencial. |
| Perder LAN con sesión activa y durante cambio | Se respeta la política documentada. Una cuenta sin verificar no se publica. |
| Recuperar LAN y repetir | Se revalida la cuenta objetivo y el S25 obtiene su nueva ficha. |
| Regresión H3 y H2 | Siguen funcionando ficha, contactos, 409, demo local explícita y lectura de la versión persistida. |

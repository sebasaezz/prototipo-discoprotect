# H4 · Guion de integración

H4 está implementado, pendiente de aceptación física. Usa el Galaxy S22 como AppAsistente, el S25 como AppPersonal y Fastify/SQLite en un PC Windows. Usa únicamente cuentas y datos ficticios.

## Identidad dinámica y ciclo de vida

AppAsistente inicia una sesión por cuenta, exige rol `ATTENDEE`, verifica `GET /me` y obtiene una credencial activa de 32 caracteres hexadecimales en `GET /me/nfc-credential`. El cliente convierte el identificador a 16 bytes antes de habilitar HCE. Los valores del seed sirven como referencias de prueba, y el identificador emitido procede de la respuesta verificada del servidor.

`HceIdentityStore` publica un estado inmutable mediante compare-and-set de `MutableStateFlow`. Incluye persona, cuenta, origen, credencial, vencimiento y generación. Una invalidación cambia la generación y elimina la credencial antes de empezar otra autenticación. Una publicación solo puede sustituir su propia invalidación. El controlador cancela operaciones anteriores y verifica su generación antes de actualizar la interfaz. Un controlador nuevo reclama el registro vacío, y la recreación normal de Activity conserva su ViewModel y la sesión en memoria.

`PersonCardService` y Activity comparten el proceso principal de AppAsistente. HCE consulta el estado vigente en memoria, verifica el plazo local con reloj de pared y tiempo transcurrido, y responde sin HTTP, disco ni dependencia de la Activity. El servicio puede operar en segundo plano mientras el proceso conserve una identidad vigente. La muerte del proceso vacía el registro, incluyendo la credencial y el Bearer. Solo se persiste la URL.

El SELECT guarda la instancia inmutable de la generación vigente. GET exige la misma instancia elegible, incluso después de una transición que vuelva a la misma cuenta. Un SELECT rechazado, GET inválido por cambio de identidad o `onDeactivated()` limpia la selección correspondiente. Sin identidad activa o ante selección obsoleta la respuesta es `6A82` sin datos. La última lectura atómica que confirma el estado vigente es el punto de decisión de la respuesta. Una invalidación posterior no retira bytes de una respuesta decidida anteriormente. [Contrato y códigos NFC](nfc-contract.md)

## Red y estados visibles

- Si ya hay una identidad verificada y vigente, una caída de LAN permite seguir emitiendo su ID local hasta el vencimiento. La app señala la desconexión al detectarla en una operación. AppPersonal puede leer NFC, pero la consulta conectada necesita Fastify.
- Al confirmar un cambio, el ID anterior queda invalidado antes del login. Si falla la red o la identidad, el teléfono permanece sin credencial. Vuelve a Conexión y toca **Reintentar verificación**.
- Tras reiniciar el proceso, se requiere login aunque la URL se haya conservado. Una revocación remota puede no conocerse de inmediato en el S22. Fastify rechaza la resolución de credenciales inactivas.
- La UI distingue selección pendiente, verificación de sesión, recuperación de credencial, cuenta verificada, cambio de cuenta, credencial ausente o inactiva, sesión vencida, desconexión y error de identidad. **Listo para compartir** exige credencial vigente, NFC activado y dispositivo compatible. **Mi ficha** y **Contactos** quedan asociados al Bearer de la cuenta confirmada. Un cambio confirmado descarta el borrador anterior.

## Preparación e instalación

1. Sigue el [guion LAN de Windows](h2-lan.md#preparar-el-servidor). En `backend/.env` usa la ruta SQLite ya validada, `HOST=0.0.0.0` y el PIN de guardia de demo. Conserva los datos de H3. Para una base nueva, ejecuta `npm.cmd ci`, `npm.cmd run db:seed` y `npm.cmd start`. Para la base ya provisionada usa `npm.cmd run build` y `npm.cmd start`.
2. Compila e instala los APK debug indicados en el [README](../README.md#android) mediante `adb -s <SERIAL> install -r <RUTA_APK>`. En el S25 puedes conservar AppPersonal de H3. Activa NFC y usa ambos teléfonos despiertos y desbloqueados en la LAN de la demostración.
3. En el S22 abre **Conexión**, indica `http://<IP-LAN>:3000` y comprueba la conexión. En el S25 inicia sesión como guardia contra la misma URL, elige **Festival Demo** y abre el lector conectado. Mantén la demo local desactivada para las pruebas remotas.
4. Selecciona cada cuenta desde **Conexión**. Ve a **Identificación**, confirma el nombre verificado y toca **Nueva lectura** en el S25 entre intentos. Separa y vuelve a acercar los teléfonos.

## Matriz física

| Caso | Pasos y resultado esperado |
| --- | --- |
| 1. Antonia | Elige Antonia. El S25 muestra Antonia Reyes con contacto y ficha de servidor. |
| 2. Martín | Cambia de cuenta y confirma. Verifica Martín Ejemplo en ambos teléfonos y sus propios contactos. |
| 3. Sofía | Repite para Sofía Ficticia. Comprueba el nombre y datos de Sofía. |
| 4. Volver a Antonia | Confirma el cambio y verifica que vuelve la ficha de Antonia con las ediciones de H3. |
| 5. Cerrar sesión | Cierra sesión en el S22 e intenta otra lectura. AppPersonal muestra un fallo de NFC y no presenta otra resolución exitosa. |
| 6. Cambiar sin LAN | Con Antonia verificada, detén Fastify y comprueba que HCE conserva su ID vigente mediante el diagnóstico del lector, aunque falle la consulta. Confirma cambio a Martín y repite. Debe fallar NFC sin emitir Antonia. |
| 7. Recuperación | Reinicia Fastify con la misma ruta SQLite, verifica de nuevo a Martín y lee en el S25. |
| 8. Segundo plano | Con sesión vigente en el S22, usa Inicio y lee con el S25. Debe leerse la misma cuenta mientras el proceso siga vivo. Mantén las condiciones de bloqueo y Secure NFC de la demo. |
| 9. Reinicio de proceso | Anota la cuenta, ejecuta `adb -s <S22> shell am force-stop cl.discoprotect.asistente`, vuelve a abrir la app y prueba NFC **antes** de autenticar. La UI debe mostrar sesión vacía y el S25 no debe obtener un ID válido. La reapertura evita confundir el estado de paquete detenido con la decisión de HCE. Rotar la Activity con una sesión vigente es una prueba separada y debe conservar la identidad. |
| 10. Editar a Martín | Edita una alergia ficticia, guarda y lee la versión de Martín en el S25. Vuelve a Antonia y verifica sus datos independientes. Usa el recorrido H3 para repetir con contactos y 409. |
| 11. Transición entre APDU | La prueba JVM de `CardSession` fija un SELECT, invalida o cambia de cuenta y envía GET. También cubre Antonia → Martín → Antonia y expiración. Para intentar reproducir en hardware hace falta un lector de prueba que separe SELECT y GET. El lector normal del S25 no ofrece esa pausa manual. |

Si el S25 señala «respuesta NFC inválida», revisa que AppAsistente tenga una identidad verificada. Si el S25 obtiene un ID pero falla la consulta, revisa Wi-Fi, firewall, rol y evento. Si NFC está apagado o Secure NFC impide el intercambio, consulta la pantalla de diagnóstico y el [contrato NFC](nfc-contract.md). Usa HTTP sin TLS exclusivamente con APK debug, datos ficticios y una LAN controlada.

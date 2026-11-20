# AppAsistente · revisión visual de Fase 1

## Entorno y evidencia

Se ejecutaron el APK de referencia H10 del merge `b733020346cb6adfb5f37cbffc779271eba6b4bc` y las compilaciones de esta rama en el AVD A52 con Android 11 (API 30). La revisión normal usó 720 × 1600 px y 320 dpi, equivalentes a 360 dp de ancho. Fastify H10 corrió en loopback con una SQLite desechable, cuentas ficticias y proveedor remoto desactivado. La respuesta del asistente IA que aparece en las capturas procede del fallback real de Fastify. Un guardia demo tomó la solicitud a través de la API autorizada.

Las imágenes PNG de ADB se conservan localmente en `.gradle/uiux-evidence/`. Se abrieron las imágenes para comprobar jerarquía, legibilidad, estados y espacio disponible. Las carpetas `antes/`, `revision-1/` y `despues/` corresponden a H10, a la primera implementación y al APK corregido. La entrada y las fichas visibles pertenecen a la base ficticia.

## Referencia H10

Se inspeccionaron las seis capturas de Identificación, Conexión, ficha, Servicios, SOS y chat. Los seis chips ocupaban tres filas a 360 dp. El SOS quedaba cerca del borde inferior. En Servicios, el cambio de evento, la actualización y las pestañas precedían al QR. En el chat, el UUID aparecía antes del estado y otra vez debajo del compositor. La ficha y Conexión tenían la misma fila de navegación sobre sus acciones.

## Primera revisión y correcciones

| Pantalla | Gravedad | Hallazgo observado | Corrección |
| --- | --- | --- | --- |
| Inicio sin sesión | Importante | «Selecciona tu evento» sugería una acción que todavía requería una cuenta verificada. | Se muestra «Sin evento seleccionado». El botón deshabilitado conserva la explicación y el acceso a Cuenta. |
| Inicio con sesión | Cosmético | El SOS era visible antes de desplazarse y su proporción permitía ver también los accesos rápidos. | Se mantuvieron tamaño y posición. |
| Inicio con incidente | Importante | El banner de recepción repetía el estado de la solicitud y alejaba la acción secundaria. | Se priorizaron el estado y «Continuar conversación». La opción de pedir ayuda adicional permite volver a la solicitud activa. |
| Ayuda | Importante | El UUID se partía en una línea adicional y, junto con el nombre propio y los campos operativos, empujaba el historial fuera de la vista. | Se usan resúmenes con estado, fecha y acción. Los datos operativos completos se abren en «Ver detalles». |
| Chat | Crítico | Al entrar el mensaje de seguridad se vio por unos segundos «Abierta» junto con «Guardia a cargo». El chat y la lista de incidentes se reconsultan por separado. | El encabezado refleja el control confirmado de la conversación cuando el estado de la lista todavía muestra un paso anterior. |
| Chat | Importante | El aviso de IA no disponible ocupaba dos bloques y la advertencia de emergencia quedaba solo en Detalles. | Se unificó el aviso y se incluyó una nota de emergencia al abrir una conversación vacía. Se mantuvieron el compositor fijo y las burbujas por autor. |
| Servicios y entrada | Crítico | El QR quedaba pegado a la barra inferior y parte del margen blanco de escaneo quedaba fuera de la primera vista incluso a 360 dp. | Se compactaron el contexto de evento y las pestañas, se centró el QR y se dejó visible el margen blanco. |
| Perfil | Cosmético | Los accesos de un mismo grupo tenían la misma separación que los grupos entre sí. | Se ajustaron los espacios dentro de cada grupo para distinguir datos personales de ajustes. |
| Cuenta y Configuración | Importante | Cuenta repetía el título y las instrucciones de verificación incluso con una sesión activa. Configuración ocupaba espacio con dos encabezados. | Cuenta muestra la tarea pendiente o el nombre confirmado. Configuración comienza directamente con la dirección y el estado. |
| Ficha | Importante | «Editar ficha» aparecía después de todos los campos, cerca del final de la pantalla de referencia. | Se colocó junto con el estado de la ficha. La actualización y los contactos siguen al final de los datos. |

## Segunda revisión en Android y ajuste de pantalla estrecha

- **Inicio y Ayuda.** En las capturas `01`, `07`, `08`, `10` y `14`, el SOS está a la vista, los impedimentos de cuenta y evento distinguen sus acciones y la solicitud activa lleva al hilo. En Ayuda, el historial aparece debajo del incidente y el UUID queda en el detalle. Las capturas `15` y `16` muestran el diálogo accesible y la apertura automática tras una pulsación prolongada real con recibo del servidor.
- **Chat.** Las capturas `09`, `16–21` y `25–26` muestran el aviso inicial, los mensajes de la persona, el fallback etiquetado como IA y el personal de seguridad. Durante el traspaso, el encabezado ya no combina «Abierta» con un guardia a cargo. Se confirmó la recuperación del borrador tras volver desde Inicio. El botón Atrás cierra primero el teclado y conserva el hilo. En una corrección adicional se mostró el nombre de cada guardia en su burbuja para conservar la autoría si cambia la asignación.
- **Servicios.** Las capturas `11–13` muestran Entrada QR, Acompañantes y Promociones con datos de Fastify. Al ensayar 320 dp y fuente al 135 %, la primera corrección aún dejaba parte del QR bajo la barra inferior. Se movieron el selector de evento y la instrucción debajo del QR cuando hay poco ancho o fuente ampliada. El QR completo y su margen se ven en la captura final `23`. ZXing decodificó el código directamente desde las capturas completas `11` y `23`. El código manual usa fuente monoespaciada y puede ocupar dos líneas al aumentar la fuente.
- **Perfil, Cuenta y Ficha.** En las capturas `02–06` y `24`, la verificación, el estado del servidor y la edición de ficha están junto a su contexto. A 320 dp se ven las cuatro etiquetas de navegación. Configuración y Diagnósticos requieren desplazar Perfil con la fuente ampliada.
- **Cancelación y conexión.** Las capturas `27–29` muestran la confirmación, el recibo de Fastify, la aparición en el historial y la lectura de la conversación cerrada. En modo avión el hilo conservó sus mensajes y mostró «Sin conexión con el chat». Al recuperar Wi-Fi desapareció el aviso y se conservó el cierre, como muestran `30–31`.

El teclado deja visible el compositor incluso al 135 %. Con el teclado abierto, un mensaje largo puede ocupar toda la zona de lectura y se puede desplazar el hilo. El código manual, la ayuda complementaria y los destinos inferiores de Perfil se mantienen accesibles mediante desplazamiento.

## Comprobaciones automatizadas

| Verificación | Resultado |
| --- | --- |
| `:emitter:testDebugUnitTest` | 51 casos aprobados. Incluye inicio del chat tras el recibo, navegación durante el envío, regreso desde Servicios, conservación del borrador y bloqueo del cambio de evento durante un reintento. |
| `:protocol:test` | 11 casos aprobados. |
| `:reader:testDebugUnitTest` | 25 casos aprobados. |
| `:emitter:lintDebug` | 0 errores y 6 avisos sobre target API, manifiesto y metadatos NFC. |
| `:emitter:assembleDebug :reader:assembleDebug` | Ambos APK debug generados. |
| Backend `chat.test.js`, `final-flows.test.js`, `venue.test.js` | 23 casos aprobados sobre datos de prueba, incluidos fallback y traspaso, permisos, QR de un solo uso, acompañantes y promociones. |

Las suites Android cubren verificación y cambio de cuenta, expiración e invalidación HCE, escrituras versionadas y resolución de conflictos, cancelación y recuperación SOS, y la validación de páginas y recibos del chat. El APK de AppPersonal conserva la presentación por defecto del componente compartido.

## Archivos de implementación

- `AttendeeLayout.kt`, `AttendeeScreen.kt` y `AttendeeProfile.kt` componen la barra inferior y los subdestinos. `EmitterScreen.kt` presenta la credencial y los diagnósticos de NFC.
- `SosScreen.kt`, `AttendeeController.kt` y `SosControllerTest.kt` coordinan Inicio, Ayuda, retorno a la conversación y el contexto compartido de evento.
- `AttendeeConversation.kt`, `IncidentChat.kt` y el manifiesto de AppAsistente acomodan la variante de chat, el teclado y los borradores. El componente compartido sigue administrando las páginas, versiones, reintentos y ciclo de vida.
- `AttendeeServicesScreen.kt` reorganiza Entrada QR, Acompañantes y Promociones. Los permisos y operaciones siguen usando las rutas H10.
- El guion H10, el flujo vigente y el Design System reflejan los nombres de los destinos nuevos. El [blueprint de esta rama](appasistente-layout-plan.md) contiene el inventario, el mapa y los riesgos.

## Repetición física en Samsung

1. Inicia una demo con una base nueva siguiendo el [guion H10](../demo-final.md). Usa `./scripts/demo-final.ps1 -Lan` y datos ficticios.
2. Compila los dos APK desde esta rama. Instala AppAsistente en el S22 y AppPersonal en el S25. Usa **Perfil → Configuración** para ingresar la URL del servidor y **Perfil → Cuenta** para verificar Antonia.
3. En **Inicio** elige Festival Demo. Comprueba la pulsación de 1,5 s y la confirmación accesible. Revisa que el hilo aparezca tras la recepción, que el guardia pueda asumir y que su respuesta se distinga de la automática. En **Ayuda** verifica la cancelación, el historial y los detalles.
4. En Perfil cambia la cuenta y verifica la nueva identidad mediante NFC en el S25. Repite con la app en segundo plano y al cerrar o vencer una sesión. Usa TalkBack y la fuente ampliada del S22 para revisar la navegación, el gesto y el diálogo de SOS.
5. Presenta **Servicios → Entrada QR** a la cámara del S25, valida una vez y comprueba «Entrada utilizada» al actualizar. Repite invitación, aceptación y desvinculación con dos cuentas ficticias. Comprueba expiración y revocación con el organizador.
6. En la LAN de prueba interrumpe la conexión durante un envío de SOS o chat, vuelve al mismo hilo y comprueba el recibo y la ausencia de duplicados. Usa el historial del servidor para distinguir una respuesta perdida de un envío nuevo.

Quedan pendientes la radio NFC/HCE, el escaneo con cámara, TalkBack y la aceptación integral en los Samsung. Se usó el A52 virtual disponible en el equipo. Los primeros intentos de arranque fallaron por memoria del host. La revisión se pudo completar con gráficos del equipo, Vulkan deshabilitado y una compilación Gradle con un trabajador.

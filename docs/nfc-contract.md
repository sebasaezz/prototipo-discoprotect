# Contrato NFC y línea base H0

El repositorio de E4 partió de `39ca32d`, un README. El punto de restauración del NFC es el commit de InnovaNFC `07da6783ea70e3afb891d1a126bc4db9e2cf47f4` disponible localmente en E2. E2 registra también `f39ac7d` y cambios de trabajo posteriores, con variaciones de UI y demostraciones locales de SOS e incidentes. De esa versión se tomó el vínculo entre el ID principal y Antonia Reyes, y se usaron teléfonos de prueba. El HTML de E2 confirma ideas de presión prolongada, clasificación por reglas y paso a seguridad que corresponden a hitos futuros.

## Intercambio

| Elemento | Bytes o valor |
| --- | --- |
| AID (categoría `other`) | `F0494E4E4F564101` |
| SELECT | `00A4040008F0494E4E4F56410100` |
| GET_PERSON_ID | `80CA000000` |
| Respuesta SELECT | `9000` si el AID es correcto y hay una credencial vigente |
| Respuesta GET exitosa | 16 bytes opacos seguidos de `9000` |
| ID demo de Antonia | `8F31A20C779145D4B522F10492AA610E` |
| IDs demo de Martín y Sofía | `00000000000000000000000000000002` y `00000000000000000000000000000003` |
| Otros códigos | `6A82` AID desconocido, app sin seleccionar, identidad ausente, vencida o selección obsoleta, `6D00` instrucción no soportada y `6700` longitud inválida |

`CardSession` reinicia la selección al desactivarse HCE o al fallar SELECT. `PersonCardService` puede atender APDU sin la Activity. Reader Mode configura NFC-A con `FLAG_READER_SKIP_NDEF_CHECK`. `NfcReader` ejecuta SELECT e IsoDep en un ejecutor, cierra la conexión antes de devolver el ID y los diagnósticos. H2 usa ese ID en la API y consulta `HealthRepository` solo en la demo local explícita. Los perfiles de H1 son Antonia Reyes, Martín Ejemplo y Sofía Ficticia. El ID permite buscar una ficha y no autentica a su portador.

El código de protocolo, sus pruebas JVM, `PersonCardService`, `NfcReader` y el XML HCE se importaron directamente y se verificaron por hash. Los manifiestos muestran los nombres nuevos de las apps. El lector conserva la lógica de Reader Mode y ciclo de vida. Las seis pruebas JVM y ambos APK se verificaron desde una copia exacta del commit histórico. En la ruta original de Windows se reprodujo `ClassNotFoundException` en el proceso de pruebas. Una ruta ASCII evita esa incidencia sin cambiar versiones.

En H3, Antonia era la identidad fija. En H4, el servidor verifica la cuenta y la credencial activa. AppAsistente la publica en memoria y HCE exige la misma generación vigente en SELECT y GET. El cambio, cierre, vencimiento o reinicio del proceso impide emitir el ID anterior. Si se pierde Wi-Fi y ya existe una credencial vigente, el servicio puede emitirla hasta el vencimiento local. [Guion H4](h4-integration.md)

La credencial NFC ficticia identifica mediante 16 bytes en RF o 32 dígitos hexadecimales en la API. El token Bearer de sesión autoriza por HTTP y se mantiene separado del intercambio APDU.

## Checklist física

1. Instala AppAsistente en Galaxy S22 y AppPersonal en Galaxy S25. Los modelos previstos proceden del README de E2. Comprueba versiones y seriales con `adb devices -l` antes de instalar.
2. Activa NFC. En el S22 verifica Antonia desde Conexión y abre Identificación NFC y verifica «Listo para compartir», ID principal y diagnóstico HCE. En el S25 abre AppPersonal, selecciona la demo local para esta comprobación de hardware y verifica «Listo para leer». Para probar la resolución en el servidor, sigue el [guion H2](h2-lan.md).
3. Con ambos desbloqueados acerca las caras posteriores a la altura de las antenas. Mantén los teléfonos quietos. El lector debe mostrar Antonia Reyes, 22 años, O+, Penicilina, Diabetes tipo 1, Insulina y María Reyes (Madre).
4. Abre el diagnóstico del lector. Comprueba detección, conexión IsoDep, SELECT correcto, ID y tiempo. La respuesta APDU contiene ID y estado.
5. Separa los teléfonos, toca Nueva lectura y repite. En el S22 vuelve a Inicio con el botón del sistema y repite desde el lector para confirmar que HCE funciona fuera de la Activity.
6. Interrumpe una lectura y repite. Prueba un tag ajeno para observar el error y la recuperación. Con una tarjeta de prueba que devuelva un ID válido sin ficha, comprueba «Identidad desconocida».
7. Desactiva NFC y vuelve al lector. Verifica el mensaje y el enlace a ajustes, y reactiva NFC. Prueba tamaños de letra grandes, temas de sistema y TalkBack en ambas apps.
8. Prueba el emisor bloqueado y con pantalla apagada si las condiciones del equipo lo permiten. El servicio solicita operar sin desbloqueo o pantalla encendida, con restricciones que dependen del sistema, Secure NFC y firmware. Para la demostración base usa el emisor despierto y desbloqueado.

Las aplicaciones tienen sus propios IDs de instalación, `cl.discoprotect.asistente` y `cl.discoprotect.personal`. Si en el S22 también está instalado el emisor de InnovaNFC, deshabilítalo para la prueba si ambos servicios compiten por el mismo AID.

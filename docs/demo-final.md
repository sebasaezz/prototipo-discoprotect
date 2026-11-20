# Demostración Discoprotect H10

## Preparación en Windows

1. Instala Node.js 22.16 o posterior, npm, JDK 21, Android SDK 36 y Platform Tools. Gradle Wrapper usa 8.13. En `local.properties` configura `sdk.dir` o define `ANDROID_HOME`. Ambas apps requieren Android con API 26 o posterior.
2. Ejecuta `npm ci` en `backend` y `web`. Crea `backend/.env` a partir de `.env.example` solo cuando falte. Define PIN distintos para organizador y los dos guardias ficticios. Para chat remoto, configura `OPENAI_API_KEY` y `OPENAI_CHAT_MODEL` en el backend. El script de demo habilita chat remoto solo con `-EnableAI`.
3. Desde la raíz ejecuta `./scripts/demo-final.ps1 -Lan` en una LAN privada. Para comprobar el proveedor real durante el recorrido, añade `-EnableAI`. Guarda la ruta de base que aparece en la consola. Deja esa terminal abierta y comprueba `curl.exe http://127.0.0.1:3000/api/v1/health`.
4. Abre otra terminal en `web` y ejecuta `npm run dev`. Entra en `http://127.0.0.1:5173` con el PIN local del organizador. Si cambiaste el puerto de Fastify, ajusta `DISCOPROTECT_API_ORIGIN` antes de iniciar Vite.
5. Ejecuta `./gradlew.bat :emitter:assembleDebug :reader:assembleDebug` en la raíz. Usa `adb devices -l` e instala con `adb -s <SERIAL> install -r <APK>`. En ambas apps introduce `http://<IPv4-LAN-del-PC>:3000` y selecciona **Festival Demo**.

| Cliente | Cuenta de demostración | Ruta del artefacto |
| --- | --- | --- |
| AppAsistente en Galaxy S22 | Antonia, Martín o Sofía | `emitter/build/outputs/apk/debug/emitter-debug.apk` |
| AppPersonal en Galaxy S25 | Guardia Demo o Guardia Demo 2 | `reader/build/outputs/apk/debug/reader-debug.apk` |
| GestiónWeb | Organizador Demo | `web/dist/index.html` y `web/dist/assets/` |

El Festival Demo se siembra con cinco zonas. Entrada principal tiene un toro mecánico y una puerta morada. Escenario tiene una estatua plateada y banderas turquesa. El puesto médico está en Entrada principal. La base incluye tres incidentes históricos ficticios, dos resueltos y uno cancelado. Se distinguen por sus fechas anteriores y las notas de semilla en las resoluciones. Antonia tiene una entrada sin usar y el evento tiene una promoción activa. Los datos se generan para la duración de la sesión de demo.

## Recorrido

1. **Organizador.** Abre Dashboard y después Eventos y Recinto y zonas. Muestra el evento activo, las referencias y el punto médico. En Personal verifica a ambos guardias. En Estadísticas muestra los dos casos resueltos y el caso cancelado de la base semilla.
2. **Asistente.** Verifica Antonia, edita una alergia ficticia en Mi ficha y revisa Contactos. Mantén activa la credencial NFC. En AppPersonal inicia como Guardia Demo, selecciona el evento y abre el lector NFC. Acerca las espaldas de los Samsung para comprobar que la ficha refleja la actualización. Cambia temporalmente a Martín y revisa que la nueva lectura corresponde a su cuenta. Vuelve a Antonia.
3. **Asistente y personal.** En SOS, selecciona Festival Demo, envía la solicitud y verifica su ID en GestiónWeb y AppPersonal. Envía «Hay alguien desmayado al lado del toro mecánico». AppPersonal debe mostrar **Ubicación probable** en Entrada principal. El asistente pregunta por respiración. Responde «Parece que no está respirando». Comprueba prioridad crítica, cita y orientación de primeros auxilios en ambas vistas de despacho. En esta simulación conserva cualquier llamada real sin realizar.
4. **Personal.** Asume, abre conversación, escribe un mensaje de prueba e inicia atención. Puedes verificar la ubicación desde el detalle y resolver con una nota ficticia. AppAsistente recibe el cierre y GestiónWeb muestra el nuevo recuento en Estadísticas.
5. **Entradas.** En AppAsistente abre Servicios del evento → Entrada. En AppPersonal ve a Incidentes → Recinto, control de entradas y búsqueda → Control de entradas. Captura el QR y valida. Para repetir el caso, introduce el mismo código manualmente o escanea una captura ficticia conservada para la prueba. El segundo intento recibe «Entrada ya utilizada». GestiónWeb muestra el consumo.
6. **Acompañantes.** Como Antonia genera una invitación en Servicios del evento. Copia el código temporal y cambia a Martín. Acepta el vínculo desde su cuenta. Vuelve a Antonia y verifica el nombre. Si hay una atención activa distinta, solo su guardia asignado puede consultar los nombres vinculados. Desvincula desde una cuenta y actualiza la otra.
7. **Promociones.** En GestiónWeb comprueba la promoción incluida o crea otra con inicio anterior a la hora actual y término futuro. Se muestra en AppAsistente → Servicios del evento → Promociones. Deshabilítala y actualiza la vista. Las promociones abiertas también se reconsultan cada 30 segundos.
8. **Administración y recuperación.** Busca el incidente exacto por ID y estado. En un evento ficticio adicional, autoriza un guardia, verifica el acceso y revócalo. Con una atención activa, elige otro guardia habilitado y sin asignación activa. Comprueba el nuevo titular y el rechazo del Bearer anterior. Reinicia Fastify con la misma base y revisa que conserva incidentes y entradas consumidas. Desconecta brevemente el teléfono de la Wi-Fi y verifica que los reintentos conservan el ID de un SOS pendiente.

## Reiniciar y restaurar

Para seguir usando una base descartable ya creada, detén Fastify y ejecuta desde `backend`:

```powershell
$env:DATABASE_PATH = 'data/final-REEMPLAZAR.sqlite'
$env:HOST = '0.0.0.0'
$env:TRIAGE_ENABLED = 'false'
$env:CHAT_AI_ENABLED = 'false'
$env:DEMO_PURGE_ENABLED = 'false'
npm start
```

Para empezar de cero, detén Fastify y vuelve a ejecutar `./scripts/demo-final.ps1 -Lan`. Se crea una ruta distinta. Si indicas `-DatabasePath`, debe corresponder a un archivo nuevo. El script abre el archivo en modo exclusivo al crearlo. Conserva las bases anteriores hasta que decidas archivarlas.

## Validación reproducible

`./scripts/validate-final.ps1` ejecuta las suites y genera el build web y ambos APK. `-Install` reinstala dependencias desde los lockfiles de npm. `-HealthPort 3000` consulta una instancia de Fastify ya iniciada. `-OpenAI` habilita la batería opcional con datos ficticios, timeout configurable, cero reintentos, máximo 20 llamadas, hasta 768 tokens de salida por llamada y tope estimado de USD 3. El valor estimado puede diferir de la facturación. [Precios de OpenAI API](https://developers.openai.com/api/docs/pricing)

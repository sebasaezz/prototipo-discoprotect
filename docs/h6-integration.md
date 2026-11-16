# H6 · Guion de integración y aceptación física

Usa solo perfiles ficticios en una LAN controlada. El Galaxy S22 ejecuta AppAsistente, el Galaxy S25 ejecuta AppPersonal y el PC Windows ejecuta Fastify, SQLite y GestiónWeb. Registra el SHA instalado, el ID de cada solicitud y el resultado de cada comprobación. La aceptación física está pendiente.

## Preparación

1. Desde la raíz, confirma que el código corresponde a la rama revisada con `git rev-parse HEAD`. Detén Fastify si está usando la base H5. Para ensayar la actualización, prepara una copia consistente de SQLite, incluido su WAL si existe, con el servidor detenido. Usa primero esa copia. Para la ejecución definitiva, conserva el `DATABASE_PATH` original y mantén `ALLOW_DEMO_RESET=false`.
2. En `backend/.env`, define `HOST=0.0.0.0`, `PORT=3000` y tres PIN privados distintos para `DEMO_STAFF_PIN`, `DEMO_STAFF_2_PIN` y `DEMO_ORGANIZER_PIN`. Conserva el archivo local. Ejecuta desde la raíz:

   ```powershell
   Push-Location backend
   npm.cmd ci
   npm.cmd run db:seed
   npm.cmd start
   ```

   `db:seed` compila, aplica la migración pendiente y agrega la cuenta demo sin sobrescribir las filas existentes. El arranque debe mostrar que Fastify escucha en el puerto configurado. Permite ese puerto en el firewall de red privada. Comprueba `http://127.0.0.1:3000/api/v1/health`. Si la migración falla, conserva SQLite y revisa el error antes de reintentar.
3. Si quedó abierta una instancia anterior de Vite, detenla para liberar el puerto 5173. En otra consola, desde la raíz, ejecuta:

   ```powershell
   Set-Location web
   npm.cmd ci
   npm.cmd run dev
   ```

   Abre `http://127.0.0.1:5173`, inicia como organizador y elige **Festival Demo**.
4. En una consola en la raíz, compila e instala. Sustituye ambos seriales por los que devuelve ADB y confirma el modelo de cada equipo.

   ```powershell
   .\gradlew.bat :emitter:assembleDebug :reader:assembleDebug
   adb devices -l
   adb -s <SERIAL-S22> install -r .\emitter\build\outputs\apk\debug\emitter-debug.apk
   adb -s <SERIAL-S25> install -r .\reader\build\outputs\apk\debug\reader-debug.apk
   ```

   Conecta los Samsung al mismo Wi-Fi y configura `http://<IP-LAN-DEL-PC>:3000` en ambas apps. En el S22 verifica Antonia. En el S25 comprueba conexión, elige **Guardia Demo**, inicia sesión y abre **Incidentes**.

## Comprobaciones físicas

| N.º | Acción | Resultado esperado |
| --- | --- | --- |
| 1 | Revisar los incidentes creados antes de la migración desde S22, S25 y web. | Coinciden UUID, estado H5, ubicación y fechas. Las fichas y sesiones vigentes siguen disponibles. |
| 2 | En el S22 elige Festival Demo y Escenario. Suelta el botón SOS antes de 1,5 s. Abre y descarta la confirmación alternativa. | El historial sigue sin un SOS nuevo. |
| 3 | Mantén pulsado SOS durante 1,5 s y registra el ID y los tiempos. | El S22 confirma la recepción. S25 y web muestran **Abierta** con el mismo ID. |
| 4 | En el S25 confirma **Asumir**. Deja las tres vistas visibles. | En menos de 10 s, el S22 y la web muestran **Asignada**. S25 y web muestran al mismo guardia. En S22 desaparece **Cancelar solicitud**. |
| 5 | En el S25 pulsa **Iniciar atención**. | En menos de 10 s, S22 y web muestran **En atención**, con el mismo ID y tiempos compatibles con S25. |
| 6 | Confirma **Resolver**, con una nota ficticia. | En menos de 10 s, las tres vistas muestran **Resuelta** y la hora de resolución coincide. La nota aparece en S25 y web. |
| 7 | Con cambios disponibles en el evento, envía la app a segundo plano y oculta la pestaña web. Vuelve a cada vista y usa luego la actualización manual. | Cada vista consulta al regresar y el botón vuelve a consultar. Los estados no vuelven a versiones anteriores. |
| 8 | Interrumpe la LAN con las pantallas abiertas y recupérala. | Durante el fallo se indica la última consulta. Al restablecer la comunicación, las vistas recuperan el estado confirmado. |
| 9 | Detén Fastify y vuelve a ejecutar `npm.cmd start` en el mismo entorno. | Las tres vistas recuperan el mismo ID, versión lógica, estado y tiempos desde la misma SQLite. |
| 10 | Crea otro SOS sin zona y cancélalo mientras está abierto. | Aparece **Cancelada** con el mismo UUID en las tres vistas. No se puede asumir. |
| 11 | Crea otro SOS. Enfrenta **Asumir** del S25 con el comando de `guardia2` del PC, usando el procedimiento de abajo. | Un único responsable confirmado. La solicitud perdedora devuelve conflicto o la vista se actualiza antes de que llegue a enviar la acción. La prueba automatizada realiza la carrera sin demora humana. |
| 12 | Con un incidente abierto, pierde la primera respuesta de **Asumir** usando el proxy de abajo. | S25 muestra **Resultado sin confirmar**. Cuando el listado vuelva, pulsa **Reintentar la misma operación**. La API confirma la operación original y el estado vigente con un solo responsable. |
| 13 | Cambia de Antonia a Martín en el S22 y envía mediante confirmación alternativa con ubicación desconocida. | Cada cuenta ve sus propias solicitudes. El guardia y el organizador del evento ven ambas. |
| 14 | En el S25 vuelve al lector. En el S22 verifica Antonia, Martín y Sofía por separado y repite una lectura nueva para cada perfil. Cierra sesión y prueba de nuevo. | Se muestra la ficha correcta para cada cuenta y, tras cerrar sesión, deja de emitirse una identidad válida. Los incidentes conservan su estado. |
| 15 | Revisa letra grande, TalkBack y teclado web en los estados y acciones modificados. | Las etiquetas se entienden sin depender del color, las acciones se alcanzan y los diálogos anuncian su propósito. |

## Segundo guardia desde PowerShell

Con otro SOS abierto, ejecuta lo siguiente en el PC. Introduce el PIN de `guardia2` en el prompt y el ID de ese SOS. Coordina el último Enter con **Asumir** en el S25.

```powershell
$apiH6 = 'http://127.0.0.1:3000/api/v1'
$staffH6 = [pscredential]::new('guardia2', (Read-Host 'PIN de guardia2' -AsSecureString))
$sessionH6 = Invoke-RestMethod "$apiH6/demo/sessions" -Method Post -ContentType 'application/json' -Body (
    @{ account = 'guardia2'; pin = $staffH6.GetNetworkCredential().Password } | ConvertTo-Json -Compress
)
$headersH6 = @{ Authorization = "Bearer $($sessionH6.token)" }
$incidentH6 = Read-Host 'ID del SOS abierto'
$urlH6 = "$apiH6/events/demo-festival/incidents/$incidentH6"
$versionH6 = (Invoke-RestMethod $urlH6 -Headers $headersH6).version
Read-Host 'Enter para competir por la asignación' | Out-Null
try {
    $bodyH6 = @{ version = $versionH6; operationId = [guid]::NewGuid().ToString() } | ConvertTo-Json -Compress
    Invoke-RestMethod "$urlH6/claim" -Method Post -ContentType 'application/json' -Headers $headersH6 -Body $bodyH6
} finally {
    Remove-Variable staffH6,sessionH6,headersH6
}
```

## Proxy para descartar una respuesta confirmada

En otra consola del PC, ejecuta este proxy temporal. Permite el puerto 3001 en el firewall de red privada y configura el **S25** con `http://<IP-LAN-DEL-PC>:3001` antes de iniciar sesión. Tras ocasionar el fallo, conserva el proxy activo y la misma URL hasta terminar el reintento.

```powershell
@'
const http = require('node:http');
let dropClaim = true;
http.createServer((incoming, outgoing) => {
  const upstream = http.request({
    hostname: '127.0.0.1', port: 3000, path: incoming.url,
    method: incoming.method, headers: incoming.headers,
  }, response => {
    if (dropClaim && incoming.method === 'POST' && incoming.url.endsWith('/claim') && response.statusCode === 200) {
      dropClaim = false;
      response.resume();
      outgoing.destroy();
      return;
    }
    outgoing.writeHead(response.statusCode, response.headers);
    response.pipe(outgoing);
  });
  upstream.on('error', () => outgoing.destroy());
  incoming.pipe(upstream);
}).listen(3001, '0.0.0.0');
'@ | node
```

## Regresión automatizada

Ejecuta `npm.cmd test` y `npm.cmd run build` en `backend` y `web`. Desde la raíz, ejecuta:

```powershell
.\gradlew.bat --offline --max-workers=2 --console=plain '-Pkotlin.compiler.execution.strategy=in-process' :protocol:test :emitter:testDebugUnitTest :reader:testDebugUnitTest :emitter:assembleDebug :reader:assembleDebug :emitter:lintDebug :reader:lintDebug :emitter:processReleaseMainManifest :reader:processReleaseMainManifest
```

Registra resultados automatizados, visuales y físicos por separado. Usa el [guion H5](h5-integration.md) para los casos de creación idempotente y el [guion H4](h4-integration.md) para la regresión NFC completa.

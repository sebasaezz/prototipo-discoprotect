# H2 en Windows y los Samsung

H2 fue validado físicamente por el usuario. Este guion conserva la preparación común de LAN y las comprobaciones de regresión. Los resultados figuran en el [checkpoint](checkpoints/h3-2026-11-15.md#validación-física-confirmada-por-el-usuario).

## Preparar el servidor

Desde el repositorio, abre PowerShell en `backend/`. Crea `.env` si todavía no existe:

```powershell
cd backend
if (!(Test-Path .env)) { Copy-Item .env.example .env }
notepad .env
```

Configura `HOST=0.0.0.0`, `PORT=3000`, `DATABASE_PATH=data/demo.sqlite` y un `DEMO_STAFF_PIN` de demostración que puedas introducir en el S25. Guarda el archivo fuera de Git. Desde `backend/`:

```powershell
npm.cmd ci
npm.cmd run db:seed
npm.cmd start
```

Abre otra terminal y verifica el proceso:

```powershell
Invoke-RestMethod http://127.0.0.1:3000/api/v1/health
ipconfig
```

Busca la IPv4 del adaptador conectado a la misma red que el S25. En el teléfono introduce `http://<IP-LAN-del-PC>:3000`. Abre esa dirección con `/api/v1/health` en el navegador del S25 si necesitas aislar un problema de conectividad.

Si el PC responde localmente y el S25 no llega al servidor, revisa que Windows use un perfil de red privada y que el firewall permita entradas TCP por el puerto elegido. En una terminal elevada, para una LAN de prueba de confianza:

```powershell
New-NetFirewallRule -DisplayName 'Discoprotect H2' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 3000 -Profile Private -RemoteAddress LocalSubnet
```

Una red de invitados puede aislar a sus clientes aunque compartan SSID. Usa una red que permita comunicación entre dispositivos. Guarda HTTP para compilaciones debug y exclusivamente para datos, PIN y token ficticios en una LAN controlada. Release requiere HTTPS. El tráfico HTTP de prueba va en claro.

## A. Conectar AppPersonal

1. Instala [ambos APK debug](../README.md#android). Activa NFC en ambos Samsung, desbloquéalos y abre AppPersonal en el S25.
2. En «Conexión del lector», introduce la URL LAN y toca «Comprobar conexión». Debe aparecer «Servidor conectado».
3. Ingresa el PIN de `guardia` configurado en el servidor y toca «Iniciar sesión». Con una membresía, AppPersonal selecciona automáticamente «Festival Demo» (`demo-festival`). Si hay varias, elige el evento de la prueba.
4. Toca «Ir al lector NFC». Comprueba el evento visible y el estado «Listo para leer».

## B y C. Leer dos versiones sin reinstalar

Desde otra terminal en el PC obtén una sesión de asistente y prepara esta función de edición:

```powershell
$api = 'http://127.0.0.1:3000/api/v1'
$antonia = Invoke-RestMethod "$api/demo/sessions" -Method Post -ContentType 'application/json' -Body '{"account":"antonia"}'
$headers = @{ Authorization = "Bearer $($antonia.token)" }

function Set-DemoAllergy([string]$allergy) {
    $card = Invoke-RestMethod "$api/me/emergency-card" -Headers $headers
    $body = @{
        version = $card.version
        bloodType = $card.bloodType
        allergies = @($allergy)
        conditions = @($card.conditions)
        medications = @($card.medications)
    } | ConvertTo-Json
    Invoke-RestMethod "$api/me/emergency-card" -Method Put -Headers $headers -ContentType 'application/json' -Body $body
}
Set-DemoAllergy 'Látex'
```

1. Anota la versión devuelta. Acerca el dorso del S22 al del S25 y mantenlos quietos durante «Leyendo NFC». Sepáralos al ver «Consultando servidor».
2. Comprueba «Fuente: servidor», Antonia Reyes, el grupo sanguíneo autodeclarado, «Látex», condiciones, medicamentos, contactos y versión. Abre el diagnóstico y comprueba SELECT correcto, ID recibido y tiempo del intercambio.
3. Ejecuta `Set-DemoAllergy 'Maní'`. Pulsa «Nueva lectura», separa y vuelve a acercar los Samsung. Comprueba «Maní» y la versión nueva en el mismo APK.

## D. Caída y recuperación

1. Detén el backend con Ctrl+C. Pulsa «Nueva lectura» y escanea. El diagnóstico debe conservar el ID recibido y la UI debe anunciar el error de conexión. La ficha anterior debe desaparecer.
2. Elige «Usar modo demo local» y escanea de nuevo. Comprueba la etiqueta «DEMO LOCAL — DATOS NO SINCRONIZADOS» y el dato local de Antonia, «Penicilina».
3. Vuelve a iniciar el backend con la misma base. En el S25 toca «Volver al modo conectado», comprueba la conexión y regresa al lector. Si la sesión sigue vigente se reutiliza en memoria. Escanea y comprueba «Maní» desde el servidor.

## E. Acceso y caducidad

- Con un PIN incorrecto, comprueba el mensaje de rechazo y reintenta con el PIN del evento.
- En una base de prueba puedes reiniciar con `npm.cmd run db:reset` siguiendo el procedimiento del README. Eso invalida las sesiones existentes. Tras arrancar el servidor, un escaneo con la sesión anterior debe requerir autenticación.
- Para reproducir un 403 de membresía, inicia sesión y selecciona el evento. En una base de prueba, retira temporalmente la membresía del guardia con el servidor detenido:

```powershell
# Desde backend/. Usa únicamente la base de prueba configurada en .env.
node --env-file=.env -e "const {DatabaseSync}=require('node:sqlite'); const db=new DatabaseSync(process.env.DATABASE_PATH); db.prepare('DELETE FROM event_memberships WHERE id = ?').run('member-demo-guardia'); db.close();"
npm.cmd start
```

  Sin cerrar la sesión de AppPersonal ni refrescar eventos, escanea otra vez. Debe mostrarse «Acceso denegado». Un 403, 401 o 404 no sustituye el resultado con una ficha local. Para recuperar la membresía, detén el proceso, ejecuta `npm.cmd run db:seed` y vuelve a iniciarlo.

## F. Repetición y ciclo de vida

Escanea varias veces con «Nueva lectura» y separación entre intentos. Interrumpe el intercambio al separar los teléfonos a mitad de lectura y comprueba el error NFC y el reintento. Durante «Consultando servidor», cambia de app o abre configuración, y verifica que una respuesta tardía no reaparece. Para empezar otra demo, sigue la [restauración del README](../README.md#backend), abre AppPersonal, comprueba la dirección guardada e inicia sesión de nuevo.

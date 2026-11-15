# H5 · Guion de integración

**Implementado y validado físicamente por el usuario el 15 de noviembre de 2026.** El [checkpoint H5](checkpoints/h5-2026-11-15.md) registra las 27 comprobaciones aprobadas. Usa Galaxy S22 como AppAsistente, Galaxy S25 como AppPersonal y un PC Windows con Fastify, SQLite y navegador. Usa datos ficticios en una LAN controlada. El SOS de esta demostración se dirige al personal del evento y no contacta a servicios públicos de emergencia.

## Preparar Windows y los teléfonos

1. En `backend/.env`, conserva el `DATABASE_PATH` de H4. Configura `HOST=0.0.0.0`, `PORT=3000` y los PIN privados de demo `DEMO_STAFF_PIN` y `DEMO_ORGANIZER_PIN`. Permite el puerto en el firewall de red privada. Conecta ambos Samsung al mismo Wi-Fi o hotspot con comunicación entre clientes. [Preparación LAN de Windows](h2-lan.md#preparar-el-servidor)
2. Con Fastify detenido, ejecuta desde `backend/` los siguientes comandos. `db:seed` aplica las migraciones y provisiona las zonas de H5, preservando las ediciones guardadas de H4.

   ```powershell
   npm.cmd ci
   npm.cmd run db:seed
   npm.cmd start
   ```

3. Desde la raíz, ejecuta `./gradlew.bat :emitter:assembleDebug :reader:assembleDebug`. Instala los APK en el S22 y S25 con `adb -s <SERIAL> install -r <RUTA_APK>`. Usa en ambas apps `http://<IP-LAN-DEL-PC>:3000`. Los APK debug permiten HTTP para la demo y los clientes release requieren HTTPS. [Rutas de los APK](../README.md#android)
4. En otra terminal, entra a `web/`, ejecuta `npm.cmd ci` y `npm.cmd run dev`. Abre `http://127.0.0.1:5173` en el navegador del PC. Vite escucha solo en loopback y reenvía `/api/v1` a Fastify. Si cambiaste el origen del backend, configura por ejemplo `$env:DISCOPROTECT_API_ORIGIN = 'http://127.0.0.1:3001'` en esa terminal. El PIN se ingresa en el formulario. El Bearer vive en memoria y vuelve a pedirse el PIN al recargar la página.

## Recorrido principal

1. En el S22, verifica **Antonia Reyes** desde **Conexión**. En **SOS**, selecciona **Festival Demo** y **Escenario**. Escribe una referencia ficticia y mantén pulsado **SOS** durante 1,5 segundos. Anota el ID que aparece con **Solicitud recibida por el servidor**.
2. En el S25, inicia sesión como guardia con su PIN. Elige el mismo evento y abre **Ver incidentes**. Comprueba el mismo ID, nombre, zona, referencia, hora y estado **Abierta**. Usa **Actualizar incidentes** si la pantalla ya estaba abierta.
3. En el navegador, inicia sesión como `organizador` con su PIN de demo. Selecciona **Festival Demo** y compara las mismas columnas con el S25.
4. Detén Fastify y vuelve a ejecutar `npm.cmd start` con la misma ruta SQLite. Actualiza los dos listados y usa **Consultar servidor** en el S22. Comprueba que siguen mostrando el mismo incidente con el mismo ID.
5. Confirma **Cancelar solicitud** en el S22. Actualiza el S25 y la web. El registro conserva su ID y aparece **Cancelada**.

## Casos adicionales

| Caso | Comprobación |
| --- | --- |
| Pulsación corta y acceso alternativo | Suelta antes de completar el indicador. Actualiza el historial y verifica que no aparece un registro nuevo. Usa **Solicitar con diálogo de confirmación** y prueba tanto volver como confirmar. |
| Martín y ubicación desconocida | Cambia a Martín, elige el evento y «No sé dónde estoy». Confirma el SOS y comprueba solicitante y ubicación en las tres superficies. El historial de Martín contiene sus solicitudes. |
| Caída de LAN antes del envío | Con el formulario listo, detén Fastify y mantén pulsado SOS. La comprobación previa debe indicar que no se inició el envío. Reinicia Fastify y verifica el historial antes de enviar. |
| Respuesta perdida | Interrumpe el retorno HTTP después de que Fastify haya persistido el POST. Para reproducirlo de forma controlada, usa un proxy de pruebas que reenvíe el primer POST SOS y descarte su respuesta. El S22 debe mostrar «No se pudo confirmar la recepción». Con la red recuperada, consulta el servidor o reintenta el mismo envío. El ID debe coincidir con el del S25 y la web. |
| Cuenta durante el envío | Mientras el S22 muestra el envío en curso, intenta seleccionar otra cuenta en **Conexión**. Tras terminar, cambia a Martín y comprueba que su historial no muestra el intento de Antonia. Si el resultado quedó incierto, vuelve a Antonia y consulta el servidor. |
| Sesión vencida | Al vencer la sesión, verifica que se ocultan los datos de la sesión y se pide iniciar sesión de nuevo. Para reproducir un 401 de inmediato, usa una base de prueba independiente con las sesiones vencidas. |
| Acceso y reenvío por API | La suite reproduce el mismo UUID en paralelo y con contenido contradictorio. También comprueba cancelación ajena, rol inválido, zonas de otro evento, sesiones vencidas y consultas fuera de membresía. |
| Regresión NFC | Vuelve a **Identificación** en el S22 y al lector en el S25. Cambia entre cuentas, realiza lecturas nuevas y comprueba ficha y contactos según el guion H4. |

## Recuperación y límites

- Antes del primer POST, AppAsistente comprueba que el backend esté disponible. Si se pierde la conexión después de iniciar el POST, la recepción queda incierta hasta obtener evidencia del servidor.
- La clave de cliente se crea antes del POST y se conserva con el cuerpo original para un reintento. El servidor serializa la comparación e inserción en SQLite. La misma persona, clave y contenido recuperan el mismo ID. Una clave repetida con otro contenido devuelve 409.
- Los intentos inciertos viven en memoria por cuenta en el origen activo. Volver a verificar la misma persona permite recuperarlos mientras siga vivo el proceso. Cambiar de origen o cerrar el proceso descarta los intentos locales. Después de volver a iniciar sesión, consulta **Mis solicitudes** en el evento antes de crear otro SOS. El historial de SQLite muestra solicitudes que sí se guardaron.
- Ante una cancelación incierta, **Consultar servidor** comprueba si el incidente ya aparece como **Cancelada**. La cancelación exige sesión de asistente, titularidad, membresía y estado abierto.
- La actualización es manual en ambas vistas operativas. AppPersonal también consulta al volver a primer plano con **Incidentes** abierto. El listado incluye solicitudes abiertas y canceladas, ordenadas por creación reciente.

## Regresión automatizada

Ejecuta `npm.cmd test` y `npm.cmd run build` en `backend/` y `web/`. En la raíz ejecuta:

```powershell
.\gradlew.bat --offline --max-workers=2 --console=plain '-Pkotlin.compiler.execution.strategy=in-process' :protocol:test :emitter:testDebugUnitTest :reader:testDebugUnitTest :emitter:assembleDebug :reader:assembleDebug :emitter:lintDebug :reader:lintDebug :emitter:processReleaseMainManifest :reader:processReleaseMainManifest
```

| Verificación | Resultado |
| --- | --- |
| Backend | 9 pruebas aprobadas y compilación correcta. La prueba incremental usa un SQLite temporal con una ficha modificada antes de aplicar H5. |
| Android | 11 pruebas NFC, 43 de AppAsistente y 19 de AppPersonal aprobadas. Se generaron los dos APK debug. Lint registró 0 errores y 6 advertencias en AppAsistente, 0 errores y 4 advertencias en AppPersonal. Los manifiestos combinados permiten HTTP en debug y lo impiden en release. |
| Web | 3 pruebas de acceso y render estático aprobadas y compilación correcta. Una prueba HTTP del proxy de Vite contra Fastify con SQLite temporal recuperó el mismo ID para guardia y organizador. |

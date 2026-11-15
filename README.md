# Discoprotect

Prototipo universitario de seguridad para eventos masivos. AppPersonal lee el identificador NFC, cierra la conexión con AppAsistente y consulta la ficha actualizada en el servidor. La demo local sigue disponible desde la configuración del lector. AppAsistente consulta y modifica la ficha y los contactos de Antonia mediante el mismo servidor.

## Backend

Usa Node.js LTS (22.13 o posterior) y npm. Desde el repositorio:

```sh
cd backend
npm ci
npm test
npm run db:migrate
npm run db:seed
npm start
```

Para habilitar roles privilegiados, copia `.env.example` a `.env` desde `backend/` (`Copy-Item .env.example .env` en PowerShell o `cp .env.example .env` en Linux). Define valores privados y distintos en `DEMO_STAFF_PIN` y `DEMO_ORGANIZER_PIN`. Los perfiles privilegiados quedan deshabilitados si su PIN está vacío. Las cuentas ficticias `antonia`, `martin` y `sofia` se pueden seleccionar sin PIN.

`npm start` ejecuta el resultado compilado. Recompila con `npm run build`. Durante el desarrollo `npm run dev` compila al inicio y reinicia Node ante cambios en el JavaScript generado. El arranque aplica migraciones pendientes. El seed y la restauración se ejecutan explícitamente. La configuración por defecto usa `127.0.0.1:3000` y `backend/data/demo.sqlite`. Consulta `GET /api/v1/health`. El flujo local funciona sin Internet tras instalar dependencias.

Para restaurar, detén el servidor, comprueba que `DATABASE_PATH` apunte a la base de prueba, establece temporalmente `ALLOW_DEMO_RESET=true` en `.env` y ejecuta `npm run db:reset`. Devuelve la opción a `false` tras restaurar. La operación recrea el evento, las cinco cuentas ficticias y sus registros relacionados, incluidas sus sesiones.

Para escuchar en LAN, configura `HOST=0.0.0.0` y `PORT` en `.env`. Permite el puerto en el firewall de red privada y usa la IP LAN del computador para las consultas. HTTP de desarrollo está destinado a datos ficticios en una LAN aislada. Fuera de ese entorno se requiere HTTPS y controles adicionales.

[Contrato API](docs/api-contract.md) · [Guion de demostración con reinicio](docs/demo-runbook.md)

## Android

JDK 21, SDK Android 36 y Gradle Wrapper 8.13. Configura `ANDROID_HOME` o `sdk.dir` en un `local.properties` de uso local. Las apps mantienen AGP 8.13.0, Kotlin 2.2.20, Compose BOM 2025.10.00, bytecode Java 17 y Android mínimo API 26.

```sh
./gradlew :protocol:test :emitter:testDebugUnitTest :emitter:assembleDebug :reader:assembleDebug :reader:testDebugUnitTest :emitter:lintDebug :reader:lintDebug
```

En Windows usa `./gradlew.bat`. Si la ruta incluye tildes y falla el proceso JVM de pruebas, ejecuta desde una ruta ASCII o crea un alias con una letra libre:

```powershell
subst T: "$PWD"
Push-Location T:\
try {
    ./gradlew.bat :protocol:test :emitter:testDebugUnitTest :emitter:assembleDebug :reader:assembleDebug :reader:testDebugUnitTest :emitter:lintDebug :reader:lintDebug
} finally {
    Pop-Location
    subst T: /D
}
```

| Aplicación | Equipo previsto | APK |
| --- | --- | --- |
| Discoprotect Asistente | Galaxy S22 | `emitter/build/outputs/apk/debug/emitter-debug.apk` |
| Discoprotect Personal | Galaxy S25 | `reader/build/outputs/apk/debug/reader-debug.apk` |

Consulta los seriales con `adb devices -l`. Instala cada APK con `adb -s <SERIAL> install -r <RUTA_APK>`. Los APK están firmados con la clave local de depuración.

[Contrato NFC y checklist Samsung](docs/nfc-contract.md) · [Design System](docs/design-system.md)

[Guion H3 para editar y leer por NFC](docs/h3-integration.md) · [Guion H2 para Windows y los Samsung](docs/h2-lan.md)

## Continuidad

[Arquitectura de las apps](docs/architecture.md) · [Modelo de datos](docs/data-model.md) · [Roadmap H0–H10](docs/roadmap.md)

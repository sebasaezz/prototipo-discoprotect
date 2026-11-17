# Guion de aceptación H7

La preparación, los escenarios H7-A/H7-B y los comandos de regresión de esta página corresponden al **diseño histórico H7** con formulario de zona, motivo y relato. Para validar el producto actual con botón SOS único y chat posterior, usa el [guion físico vigente](h7-2-integration.md). La evidencia de aquella etapa queda en el [checkpoint H7](checkpoints/h7-2026-11-16.md).

## Preparación

1. Compila el backend y ambos APK debug y prepara una base SQLite descartable con `npm.cmd run db:seed` desde `backend`. Si reutilizas una copia de una base H6, detén Fastify antes de copiarla y deja que el arranque aplique la migración 004. Mantén un solo proceso Fastify por base.
2. Selecciona la dirección LAN del PC en ambas apps e inicia sesión con `antonia` en el S22 y `guardia` en el S25. En GestiónWeb inicia sesión como `organizador`. Usa los PIN locales y selecciona el mismo evento.
3. Para H7-A arranca Fastify con `TRIAGE_ENABLED=true`, `OPENAI_MODEL=gpt-5.4-mini` y `OPENAI_API_KEY` configurada de forma local. Conserva `TRIAGE_TIMEOUT_MS=5000` y `TRIAGE_MAX_QUEUE_AGE_MS=30000` para los valores iniciales. Para acotar la aceptación, usa `TRIAGE_DAILY_LIMIT=1` en una base de prueba nueva.

## H7-A · Sugerencia, corrección y persistencia

1. En S22 elige zona y el motivo «Malestar o lesión». Introduce el relato ficticio «Se fue de hocico y quedó medio ido, pero está hablando.» Envía el SOS y registra el ID del recibo.
2. En S25 localiza el mismo ID y espera «Sugerencia de IA». Abre «Ver triaje» y comprueba que aparecen el relato original, el resumen y la incertidumbre. Asume el SOS y usa «Corregir clasificación» para guardar una categoría o prioridad distinta de la sugerida.
3. En GestiónWeb comprueba la clasificación corregida con etiqueta «Revisión humana», autor y fecha en «Detalle e historial». Reinicia Fastify sobre la misma base. Usa una sesión autorizada y verifica el mismo ID, la clasificación corregida, la sugerencia original y el historial persistido. El relato original se consulta desde el S25.

## H7-B · Proveedor no disponible y atención manual

1. Detén Fastify y arráncalo con `TRIAGE_ENABLED=false`, simulando que el proveedor está indisponible sin cortar la LAN. Conserva la misma base y el mismo evento.
2. En S22 envía otro SOS con zona, motivo explícito «Peligro inmediato» y relato ficticio «Hay una pelea en curso y la gente se está alejando». Comprueba que el servidor confirma la recepción.
3. En S25 verifica que aparece «Triaje manual» junto con «Se reportó peligro inmediato». Completa «Asumir», «Iniciar atención» y «Resolver». Comprueba en GestiónWeb el estado «Resuelta» y la alerta correspondiente.

## Regresión automatizada

Ejecuta `npm.cmd test` y `npm.cmd run build` en `backend` y `web`. Desde la raíz:

```powershell
.\gradlew.bat --offline --max-workers=2 --console=plain '-Pkotlin.compiler.execution.strategy=in-process' :protocol:test :emitter:testDebugUnitTest :reader:testDebugUnitTest :emitter:assembleDebug :reader:assembleDebug :emitter:lintDebug :reader:lintDebug :emitter:processReleaseMainManifest :reader:processReleaseMainManifest
```

La validación física H7 se limita a H7-A y H7-B. Las pruebas del protocolo NFC se ejecutan automáticamente.

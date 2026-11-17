# Aceptación H7.2

Usar exclusivamente casos ficticios y una LAN de desarrollo controlada. Ejecutar las pruebas y guardar la evidencia por separado para backend, revisión visual y hardware.

## Preparación

1. Compilar backend, GestiónWeb y ambos APK debug. Instalar AppAsistente en el S22 y AppPersonal en el S25.
2. Abrir PowerShell en el PC y definir `DATABASE_PATH` como una ruta descartable nueva. Usar la misma ruta para la semilla y Fastify, con `ALLOW_DEMO_RESET=false`. Desde `backend`, ejecutar `npm.cmd run db:seed` y luego `npm.cmd run start`. En LAN controlada, configurar `HOST=0.0.0.0` y la dirección LAN del PC en ambas apps. Ejecutar GestiónWeb con el proxy de desarrollo documentado en el README.
3. Elegir el mismo evento en S22 (`antonia`), S25 (`guardia`) y GestiónWeb (`organizador`). Usar los PIN privados locales. Para comprobar un segundo guardia, usar `guardia2` en otra instancia autorizada o una sesión HTTP de prueba.

## Guion físico mínimo

| Caso | Acción | Resultado esperado |
| --- | --- | --- |
| Recepción y ayuda | En S22 enviar «Hay una persona que no responde cerca del escenario». Anotar ID y hora. | El mismo ID aparece en S25 sin esperar al proveedor. Si llega una aclaración, tiene etiqueta «Asistente IA». Verificar el estado confirmado y, si el análisis cita el peligro, P0 provisional. |
| Traspaso y ubicación | En S25 elegir «Asumir conversación», responder en el chat, registrar ubicación y prioridad con motivo, e iniciar atención y resolver. En S22 responder antes del cierre. | Un guardia queda asignado. El inicio de atención se refleja solo después de su operación. Desde la asignación el chat recibe mensajes humanos y conserva las intervenciones anteriores. S25 muestra las procedencias de ubicación y GestiónWeb la corrección y las transiciones. |
| SOS y reconexión | Reiniciar Fastify con IA desactivada. Desde S22 enviar SOS sin texto con la pulsación mantenida o el diálogo accesible. Cortar y recuperar Wi-Fi durante un envío y reintentar el mismo mensaje si queda incierto. | SOS confirmado y en cola para evaluación. Se recupera el historial y hay una sola copia de cada mensaje confirmado. Tras reiniciar, permanecen los IDs y la auditoría. |
| NFC y accesibilidad | Volver a Identificación en S22 y al lector en S25. Leer la credencial ficticia; revisar estados con TalkBack y texto grande en ambos teléfonos. | Se abre la ficha autorizada. Se entienden interlocutor, estado, prioridad y controles con texto e iconos. |

## Regresión y llamada real acotada

Ejecutar `npm.cmd test` y `npm.cmd run build` en backend y web. Desde la raíz:

```powershell
.\gradlew.bat --offline --max-workers=2 --console=plain '-Pkotlin.compiler.execution.strategy=in-process' :protocol:test :emitter:testDebugUnitTest :reader:testDebugUnitTest :emitter:assembleDebug :reader:assembleDebug :emitter:lintDebug :reader:lintDebug :emitter:processReleaseMainManifest :reader:processReleaseMainManifest
```

En la etapa 5, con la credencial local disponible, `npm.cmd run smoke:chat` desde `backend` realiza una llamada real con un relato ficticio, SQLite en memoria y una sola reserva. Comprueba que el recibo y la cola están disponibles antes de llamar a Responses y registra solo estado, modelo, prioridad, latencia y contadores.

La ejecución del 16 de noviembre de 2026 confirmó HTTP 201 antes de llamar al proveedor. OpenAI devolvió HTTP 200 para `gpt-6.1-sol` con esfuerzo `low`. Fastify persistió el análisis en 6,29 s, con 551 tokens de entrada y 163 de salida. El incidente permaneció abierto con P0 provisional. La aceptación física se registra aparte.

La regresión automatizada aprobó 32 pruebas de backend, 9 de GestiónWeb, 47 de AppAsistente, 22 de AppPersonal y 11 del protocolo. La revisión visual de GestiónWeb se cubrió mediante renderizado estático en las pruebas. La revisión en pantalla y la aceptación física de S22 y S25 quedan pendientes.

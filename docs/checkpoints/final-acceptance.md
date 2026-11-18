# Aceptación de Discoprotect H0–H10

Rama `codex/discoprotect-final-h10`. La validación en PC y la batería real de OpenAI se ejecutaron con datos ficticios. La comprobación final en los dos Samsung queda abierta. Consulta el [guion reproducible](../demo-final.md).

| Hito | Función integrada | Evidencia |
| --- | --- | --- |
| H0 | AID, APDU, HCE, Reader Mode e IsoDep | Suite JVM del protocolo. Aceptación física histórica en los checkpoints previos. |
| H1 | API, SQLite y diseño compartido | Salud de Fastify, migraciones y pruebas API. |
| H2 | Lectura NFC que resuelve ficha por LAN | Contrato API y Kotlin. Validación física histórica. |
| H3 | Edición versionada de ficha y contactos | API, persistencia y regresión Android. [Checkpoint H3](h3-2026-11-15.md). |
| H4 | Sesión seleccionable e identidad NFC dinámica | Pruebas de cambio de cuenta, caducidad y acceso por evento. [Checkpoint H4](h4-2026-11-15.md). |
| H5 | Emisión SOS persistente e idempotente | Reintentos y reinicio HTTP con SQLite real. [Checkpoint H5](h5-2026-11-15.md). |
| H6 | Asignación, atención, resolución y sincronización | Pruebas de contención, ciclo de vida y persistencia. [Checkpoint H6](h6-2026-11-15.md). |
| H7–H7.3 | Triaje, chat, despacho, auditoría y control de personal | Regresión automatizada y [6/6 comprobaciones físicas históricas](h7-3-2026-11-17.md). |
| H8.1 | Dashboard y navegación en GestiónWeb | Pruebas React. Integrado por PR #8, merge `72774a7`. |
| H8.2 | Recinto, zonas, inferencia inmediata y triaje progresivo | Pruebas simuladas y batería con OpenAI real. |
| H8.3 | Estadísticas del evento | Filtros y agregados contrastados contra SQLite, incluida la frontera de medianoche en Chile. |
| H8.4 | Eventos, personal, revocación y reemplazo | Eventos, concurrencia e idempotencia. Tras revocar se rechaza el Bearer anterior y se conserva la fase de atención en el reemplazo. |
| H9 | Entrada QR, validación, acompañantes, búsqueda y promociones | API y builds completos. La captura y lectura QR en teléfonos requiere comprobación física. |
| H10 | Demo reproducible, scripts y estabilización | Fastify y SQLite sobre base descartable, pruebas HTTP reales, compilación web y Android. Aceptación física final pendiente. |

## Pruebas y correcciones

- Backend. 54 pruebas aprobadas con Node 26.13.1. Incluyen SQLite en memoria y en disco, migraciones desde versiones históricas, Fastify y reinicio HTTP real, duplicación de SOS, triaje, fallback, estadísticas, membresías, permisos y vigencias. Se corrigió el acceso de la semilla a una tabla ausente en bases históricas. La revisión del arranque de demo detectó incidentes semilla sin fila de triaje. Se añadió la inicialización y una prueba que valida el despacho y los tres históricos de esa base. El sandbox impedía la conexión HTTP de bucle local y la prueba pasó al ejecutarla con el permiso de red correspondiente. Las regresiones de ubicación comprueban que una propuesta inválida del proveedor queda fuera del despacho aunque el catálogo local produzca evidencia válida por su cuenta.
- GestiónWeb. 19 pruebas aprobadas y compilación de producción correcta. Se corrigieron tipos de despacho, referencias de React y el fixture de navegación tras activar las secciones de H8–H9. La herramienta de navegador agotó el tiempo al abrir una pestaña local y al intentar inventariar las superficies. La revisión visual interactiva queda pendiente.
- Android. Gradle 8.13 con JDK 21.0.3 y SDK 36 compiló AppAsistente y AppPersonal. Pasaron 11 pruebas del protocolo, 48 de AppAsistente y 25 de AppPersonal. ADB detectó solo `emulator-5554` en estado `offline`. Instalación, capturas en teléfonos, NFC, cámara y pérdida de LAN en dispositivos quedan pendientes.
- Seguridad. Las pruebas verificaron separación de QR y NFC, control por evento y rol, acceso asignado de acompañantes, recibos idempotentes, caducidad, revocación, operaciones concurrentes y catálogos con instrucciones incrustadas. Los permisos y estados productivos dependen de Fastify.

## OpenAI real

El runner `backend/scripts/acceptance-openai.mjs` usó `gpt-6.1-sol` con datos ficticios, base en memoria y endpoint oficial. Pasaron 11 llamadas con JSON estructurado aceptado, 3 turnos resueltos localmente y una revisión humana. Se evaluaron desmayo con respiración desconocida, ausencia de respiración normal, abstención ante ambigüedad, cambio de zona, palabras coloquiales con erratas, negación, instrucciones incrustadas, conversación no urgente, continuidad y autoridad del guardia. «Sí», «no» y «no sé» se resolvieron a partir de la pregunta real, sin llamadas adicionales. El fallback frente a fallo del proveedor se comprobó en la suite simulada.

OpenAI reportó 11.927 tokens de entrada y 2.030 de salida. Coste estimado USD 0,04415 con cero reintentos y timeout de 15 segundos. La batería se limita a 20 llamadas, 768 tokens de salida por llamada y un coste estimado de USD 3. La facturación puede diferir de la estimación. [Precios de OpenAI API](https://developers.openai.com/api/docs/pricing)

La orientación de primera respuesta proviene de texto fijo del backend. El runner comprobó la orientación sobre SAMU y RCP en el escenario ficticio de respiración anormal y la ausencia de promesas de envío. El despacho conserva el relato y la evidencia. Una revisión del guardia puede confirmar lugar o resolver discrepancias de prioridad. [AHA adultos](https://cpr.heart.org/en/resuscitation-science/cpr-and-ecc-guidelines/adult-basic-life-support/) · [AHA pediatría](https://cpr.heart.org/-/media/CPR-Files/CPR-Guidelines-Files/2025-Algorithms/Diagram-Ped-BLS-Lay-Rescuers-250121.pdf) · [SAMU 131](https://saludresponde.minsal.cl/fiestas-patrias-samu/)

## Migraciones y reproducción

Se aplicaron `012_event_staff.sql`, `013_event_services.sql` y `014_local_chat_locations.sql` en bases nuevas y de prueba actualizadas. Se corrió `PRAGMA foreign_key_check`. Para la demo se generó SQLite nueva con `scripts/demo-final.ps1`. Las bases valiosas permanecieron fuera de las pruebas. Consulta el [modelo](../data-model.md) y la [guía de instalación y uso](../demo-final.md).

Se detectó `backend/.env` y se usó desde el proceso de la batería real. Su contenido permanece fuera de Git. En la raíz y en `backend/` la comprobación no encontró un archivo `.dev`.

`scripts/validate-final.ps1` genera ambos APK y el build de GestiónWeb. `-OpenAI` ejecuta la prueba real, que es opcional. Artefactos locales en `emitter/build/outputs/apk/debug/emitter-debug.apk`, `reader/build/outputs/apk/debug/reader-debug.apk` y `web/dist/`.

## Decisiones y límites

- Una entrada por persona y evento. Sólo Fastify confirma el consumo y permite recuperar el primer resultado mediante el mismo UUID.
- La revocación de un guardia cierra sus sesiones existentes. Con una atención activa, el reemplazo debe ser autorizado y estar libre de asignaciones activas. Se conserva la fase de la atención.
- Las fechas de consolidados usan `America/Santiago`, las horas de formularios usan la zona del navegador y se almacenan en UTC. Las solicitudes sin marcas quedan fuera de los promedios correspondientes.
- Las búsquedas usan catálogos de evento o coincidencias exactas. El perfil médico continúa accesible mediante NFC autorizado. Las promociones muestran texto y enlaces HTTPS opcionales. AppAsistente abre las imágenes promocionales mediante una acción del usuario.
- Los escenarios físicos pendientes están concentrados en el [guion](../demo-final.md). El estado físico previo de H0–H7 sigue documentado en sus checkpoints.

## Historial de referencia

- `72774a7` integra H8.1.
- `5a419c8` cierra el refactor H8.2 previo.
- `c39193a` integra las dos líneas de trabajo.
- `72df98f` incorpora la señal visual inmediata y las pruebas de conversación H8.2.
- `6a9190a` integra las estadísticas, la administración de personal y eventos y los servicios H9.

La instalación de GitHub respondió `403 Resource not accessible by integration` al crear un commit remoto. La publicación y el PR quedan pendientes. Los commits de entrega se agrupan en la referencia `codex/discoprotect-final-h10` del repositorio local aislado bajo `.tools/final-delivery-git`.

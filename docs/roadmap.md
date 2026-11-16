# Roadmap H0–H10

El [checkpoint H3](checkpoints/h3-2026-11-15.md) conserva la evidencia de cierre. Los checkpoints [H4](checkpoints/h4-2026-11-15.md), [H5](checkpoints/h5-2026-11-15.md) y [H6](checkpoints/h6-2026-11-15.md) registran sus aceptaciones físicas.

| Hito y estado | Objetivo, componentes y dependencias | Entregable y aceptación | Pruebas y riesgos |
| --- | --- | --- | --- |
| H0 · Implementado, NFC físico validado por el usuario | Auditar `protocol`, `emitter`, `reader`. Requiere InnovaNFC, JDK 21 y SDK 36. | Contrato APDU, referencia reproducible y checklist para Samsung. | Ejecutar las JVM y compilar ambos APK. Validar físicamente el intercambio. Riesgos de ruta Windows y Secure NFC. |
| H1 · Implementado, NFC físico validado por el usuario | Base REST/SQLite y diseño Android. Depende de H0 y Node LTS. | Actualizar ficha, reiniciar y resolver por NFC usando una sesión de guardia. Apps NFC locales con apariencia nueva. | API, persistencia, autorización, build y lint. Revisión de ambas apps en emulador sin adaptador NFC. Validar los estados con hardware en dispositivos. Riesgos de sesiones demo y configuraciones LAN. |
| H2 · Implementado, validación física integrada confirmada por el usuario | Conectar lectura de AppPersonal a la API H1. | Tras leer NFC, mostrar la ficha actualizada del servidor y distinguir fallback local explícito. | Escaneo entre dos Samsung y caída de LAN. Riesgos de latencia y resultados desactualizados. |
| H3 · Implementado y validado físicamente por el usuario el 2026-11-15 | Edición de ficha y contactos desde AppAsistente. Depende de H2. | Guardar desde AppAsistente y recuperar el cambio por NFC en AppPersonal. | Flujos completos, errores y conflictos de versiones. Riesgo de edición concurrente. |
| H4 · Completado y validado físicamente el 15 de noviembre de 2026 | Sesión seleccionable y credenciales dinámicas. Depende de H3. | Cambiar de cuenta y obtener su ficha por NFC. | Alternar identidades y verificar consistencia HCE al reanudar. Riesgo de credencial anterior. |
| H5 · Implementado y validado físicamente el 15/11/2026 | SOS persistente, zona manual, guardia y vista web básica. Depende de H4, API y LAN. | Una pulsación prolongada crea un incidente que conserva el mismo ID en AppPersonal y en la web tras reiniciar Fastify. | [Aceptación H5, 27/27](checkpoints/h5-2026-11-15.md) · [Guion de regresión](h5-integration.md). |
| H6 · Implementado, validado e integrado en `main` por PR #6 | Atención, transiciones y sincronización. Depende de H5. | Un guardia asume, atiende y resuelve. Asistente y GestiónWeb reciben los estados. | [Checkpoint H6](checkpoints/h6-2026-11-15.md). 95 pruebas automatizadas reportadas y 26/26 comprobaciones físicas aprobadas, incluida la regresión NFC. |
| H7 · Implementado en rama de trabajo, pendiente de revisión y aceptación física | Triaje con OpenAI, alertas deterministas, fallback y revisión humana sobre H6. | Motivo y relato opcionales, sugerencia no bloqueante y corrección trazable. | [Checkpoint H7](checkpoints/h7-2026-11-16.md) · [Guion H7](h7-integration.md). |
| H8 · Pendiente | GestiónWeb para organizadores. Depende del API de eventos e incidentes. | Administrar eventos/personal y consultar métricas e historial persistentes. | Permisos, consistencia de métricas y flujos web. Riesgo de exponer registros de otro evento. |
| H9 · Pendiente | Entradas QR ficticias, promociones, acompañantes y búsqueda autorizada. Depende de apps y GestiónWeb. | Publicar una promoción y verla durante la vigencia. Completar flujos complementarios. | Vigencias, zonas horarias, accesos y entradas inválidas. Riesgo de campañas fuera de plazo. |
| H10 · Pendiente | Estabilizar apps y demo integrada. Depende de H0–H9. | Ejecutar el guion completo con los dos Samsung y el computador, con datos restaurables. | E2E, reinicio, caída de LAN y fallback IA. Riesgos de hardware, firmware y red del recinto. |

## Plan de ejecución H6–H10 · Visión integral

### H6 · Gestión operativa de incidentes

- **Objetivo y base.** Convertir el SOS de H5 en una solicitud que el personal del evento pueda asignar, atender y resolver. Reutilizar la API Fastify, SQLite, la membresía por evento y las vistas de AppAsistente, AppPersonal y web. Extender el modelo de incidentes `OPEN`/`CANCELLED`.
- **Diseño y riesgo.** Acordar estados, transiciones, permisos, concurrencia y reglas de cancelación antes de fijar el contrato. Fastify y SQLite conservan la autoridad. Evaluar primero consulta periódica o actualización explícita con reconexión. El riesgo principal es que dos guardias actúen sobre el mismo estado o que una vista muestre un estado obsoleto.
- **Aceptación física.** El guardia recibe, asigna, atiende y resuelve un SOS. El asistente y el organizador consultan el estado persistido, incluso tras recuperar la conexión. El triaje IA y la administración de eventos quedan para H7 y H8.

### H7 · Triaje asistido por IA

- **Objetivo y base.** Procesar el motivo y el relato opcionales con OpenAI fuera del envío del SOS. Conservar alertas por peligro explícito, fallback manual y revisiones versionadas con autor.
- **Aceptación física.** H7-A verifica sugerencia sobre relato ficticio, corrección en S25 y persistencia en GestiónWeb tras reiniciar. H7-B verifica recibo y atención manual completa con OpenAI indisponible. [Guion H7](h7-integration.md)

### H8 · GestiónWeb para organizadores

- **Objetivo y base.** Evolucionar la vista React de H5 para administrar eventos y personal autorizado y consultar incidentes, historial y métricas. Reutilizar sesiones, miembros por evento y API operativa de H6. Definir permisos administrativos antes de implementar. Las pantallas y el diseño de permisos pueden planificarse en paralelo a H7, con la API de eventos e incidentes como dependencia principal.
- **Aceptación física.** Desde el navegador, un organizador administra los datos autorizados de un evento y verifica que el historial y las métricas persisten y concuerdan con las apps. Prueba que otro evento queda fuera de su acceso. El riesgo principal es la filtración entre eventos. Entradas, acompañantes y promociones se reservan para H9.

### H9 · Flujos complementarios de eventos

- **Objetivo y base.** Incorporar entradas QR ficticias, acompañantes, promociones y búsqueda autorizada. Reutilizar cuentas, eventos, permisos, apps y GestiónWeb de H8. Mantener estos módulos independientes de SOS y NFC. Definir vigencias y zonas horarias, relaciones entre personas y eventos y reglas de acceso.
- **Aceptación física.** En un evento de prueba se valida una entrada QR ficticia, se consulta la relación de acompañantes, se realiza una búsqueda autorizada y se comprueba que la promoción solo aparece durante su vigencia y según permisos. El riesgo principal son las inconsistencias de acceso o vigencia. Pagos y ticketing comercial quedan fuera del alcance.

### H10 · Estabilización integral y demostración final

- **Objetivo y base.** Consolidar H0–H9 en un recorrido reproducible con S22, S25, Fastify, SQLite y navegador. Preparar instalación, datos ficticios restaurables, regresión, accesibilidad y recuperación ante pérdida de red o reinicio.
- **Aceptación física.** Reproducir el recorrido completo de NFC, SOS, gestión operativa, IA con fallback, administración y funciones complementarias. Verificar que los datos persisten tras reiniciar y que la demo vuelve al estado de prueba previsto. El riesgo principal son las fallas de integración en el hardware y la LAN del recinto. H10 estabiliza el alcance anterior y deja fuera módulos nuevos.

### Decisiones transversales

- Entregar flujos completos y verificables por hito. Reutilizar módulos y contratos. Validar físicamente antes de integrar a `main`.
- Aplicar migraciones SQLite incrementales que conserven datos. Autorizar por sesión, rol y evento. Mantener persistencia e idempotencia en operaciones críticas y distinguir desconexión de resultado incierto.
- Preservar NFC H0–H4 y separar datos de emergencia de servicios complementarios. Mantener la IA no bloqueante y bajo supervisión humana. Usar datos ficticios y documentar los límites de seguridad del prototipo.

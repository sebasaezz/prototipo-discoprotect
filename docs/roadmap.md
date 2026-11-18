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
| H7–H7.3 · Integrados en `main` por el PR #7 | Triaje, chat SOS, prioridad efectiva, traspaso, atención, retiro y purga demo. | Recorrido completo sobre SQLite con asignación activa única y auditoría. | 6/6 comprobaciones físicas del flujo principal aprobadas. [Checkpoint previo al merge](checkpoints/h7-3-2026-11-17.md) · [Guion físico](h7-2-integration.md). |
| H8.1 · Integrado a `main` en el PR #8 | Dashboard y reorganización de GestiónWeb. | Resumen real del evento autorizado y navegación responsive con las acciones de H7.3. | Conteos, aislamiento por evento, sincronización y reintentos administrativos. |
| H8.2 · Implementado en `codex/discoprotect-h8-2` | Recinto, zonas y puntos operativos del evento. | Configuración versionada, catálogo activo para AppPersonal y contexto validado para el chat asistido. | Permisos, trazabilidad, reintentos de SOS y pruebas de IA simulada. |
| H8.3–H8.4 · Pendientes | Estadísticas privadas y personal autorizado. | Ampliar las métricas y la gestión de guardias del evento. | Aislamiento entre eventos, consistencia de métricas y auditoría de cambios. |
| H9 · Pendiente | Entradas QR ficticias, promociones, acompañantes y búsqueda autorizada. Depende de apps y GestiónWeb. | Publicar una promoción y verla durante la vigencia. Completar flujos complementarios. | Vigencias, zonas horarias, accesos y entradas inválidas. Riesgo de campañas fuera de plazo. |
| H10 · Pendiente | Estabilizar apps y demo integrada. Depende de H0–H9. | Ejecutar el guion completo con los dos Samsung y el computador, con datos restaurables. | E2E, reinicio, caída de LAN y fallback IA. Riesgos de hardware, firmware y red del recinto. |

## Plan de ejecución H6–H10 · Visión integral

### H6–H7.3 · Gestión operativa integrada

H6 y H7–H7.3 están integrados a `main`. El flujo principal de H7.3 aprobó 6/6 comprobaciones físicas. [PR #7](https://github.com/sebasaezz/prototipo-discoprotect/pull/7) · [Flujo actual](current-workflow.md)

### H8 · Administración de eventos y personal

- **H8.1 · Dashboard.** Mostrar el resumen operativo del evento autorizado y reorganizar cola, detalle, historial, retiro y purga demo en **Incidentes**. Las secciones futuras se muestran en desarrollo. [Flujo de GestiónWeb](current-workflow.md#gestiónweb-h81)
- **H8.2 · Recinto y zonas.** Amplía la edición del recinto y usa el catálogo como contexto del asistente progresivo. El despacho separa la zona probable, la evaluación inmediata del riesgo y la verificación del guardia. [Flujo de comprobación](current-workflow.md#recinto-y-zonas-h82)
- **H8.3 · Estadísticas.** Incorporar métricas históricas privadas del evento con definiciones, filtros y gráficos verificables.
- **H8.4 · Personal.** Gestionar las membresías de guardias del evento y definir el retiro de acceso y la reasignación, preservando el historial y la unicidad de las asignaciones activas.

El acceso se limita a las membresías del evento. Los roles son `ATTENDEE`, `STAFF` y `ORGANIZER`. Las entradas, acompañantes y promociones se implementarán en H9.

### H9 · Flujos complementarios de eventos

- **Objetivo y base.** Incorporar entradas QR ficticias, acompañantes, promociones y búsqueda autorizada. Reutilizar cuentas, eventos, permisos, apps y GestiónWeb de H8. Mantener estos módulos independientes de SOS y NFC. Definir vigencias y zonas horarias, relaciones entre personas y eventos y reglas de acceso.
- **Aceptación física.** En un evento de prueba se valida una entrada QR ficticia, se consulta la relación de acompañantes, se realiza una búsqueda autorizada y se comprueba que la promoción solo aparece durante su vigencia y según permisos. El riesgo principal son las inconsistencias de acceso o vigencia. Pagos y ticketing comercial quedan fuera del alcance.

### H10 · Estabilización integral y demostración final

- **Objetivo y base.** Consolidar H0–H9 en un recorrido reproducible con S22, S25, Fastify, SQLite y navegador. Preparar instalación, datos ficticios restaurables, regresión, accesibilidad y recuperación ante pérdida de red o reinicio.
- **Aceptación física.** Reproducir el recorrido completo de NFC, SOS, gestión operativa, IA con fallback, administración y funciones complementarias. Verificar que los datos persisten tras reiniciar y que la demo vuelve al estado de prueba previsto. El riesgo principal son las fallas de integración en el hardware y la LAN del recinto. H10 estabiliza el alcance anterior y deja fuera módulos nuevos.

### Decisiones transversales

- Entregar flujos completos y verificables por hito. Reutilizar módulos y contratos. Registrar la aceptación física por separado y documentar su estado al integrar a `main`.
- Aplicar migraciones SQLite incrementales que conserven datos. Autorizar por sesión, rol y evento. Mantener persistencia e idempotencia en operaciones críticas y distinguir desconexión de resultado incierto.
- Preservar NFC H0–H4 y separar datos de emergencia de servicios complementarios. Mantener la IA no bloqueante y bajo supervisión humana. Usar datos ficticios y documentar los límites de seguridad del prototipo.

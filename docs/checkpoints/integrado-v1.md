# Integración de H10, UI/UX Android e IA

La rama `codex/discoprotect-integrado-v1` es la base para el mapa interactivo del Parque O’Higgins y la geolocalización SOS de Lollapalooza Chile 2027. Esos cambios partirán de esta rama o del commit de `main` que la incorpore tras la revisión.

## Origen e historial

| Origen | SHA remoto verificado | Incorporación |
| --- | --- | --- |
| `main` | `b733020346cb6adfb5f37cbffc779271eba6b4bc` | Base H10 del PR #9 |
| `codex/discoprotect-uiux-apppersonal-layout-v1` | `f98c2f78a9ae53f02312b35c48d795162167de21` | Merge `15738e6`. Contiene también el rediseño de AppAsistente y el branding de ambas apps. |
| `codex/discoprotect-ai-demo-relaxed-v1` | `84911082bddf2632504ad4f180e76c9a89197737` | Merge `cfd1463`. Incluye Entrada Perry's Stage de `74d5f38`. |

Git integró ambos historiales sin conflictos. Los cambios en `docs/current-workflow.md` y `docs/demo-final.md` se combinaron automáticamente. Se revisaron los destinos Android, el caso de Perry’s Stage, las variables de IA y las instrucciones de validación. Los árboles de Android y NFC coinciden con la rama UI/UX. Los árboles del backend y GestiónWeb coinciden con la rama de IA.

## Funcionalidad integrada

- **AppAsistente** conserva Inicio, Ayuda, Servicios y Perfil, con SOS, chat, ficha y contactos, HCE, entradas QR, acompañantes y promociones.
- **AppPersonal** conserva Inicio, Incidentes, Herramientas y Perfil, con lector NFC, fichas, cola, chat, despacho y revisión de prioridad y ubicación, ciclo de atención y validación de entradas.
- **Fastify y GestiónWeb** conservan los contratos REST, SQLite, roles, permisos del evento y gestión de personal, incidentes, zonas, entradas, promociones y estadísticas. La IA admite 32.768 bytes de contexto, hasta 64 zonas, 16 mensajes recientes y diagnósticos opcionales.
- **Chat y despacho** conservan autoría, control `AUTO` y `STAFF`, evidencias del relato y confirmación humana de ubicaciones. Los nombres con apóstrofos se muestran como parte de la ubicación probable.

## Validación

| Componente | Resultado |
| --- | --- |
| Backend | Compiló. 56 pruebas aprobadas, incluidos el reinicio HTTP con SQLite, Perry’s Stage, autenticación, contratos REST, permisos, despacho, traspaso y servicios del evento. |
| GestiónWeb | Compiló. 19 pruebas aprobadas, incluidos el consumo del contrato REST, permisos y ubicación probable en despacho. |
| AppAsistente | APK debug compilado. 51 pruebas aprobadas. |
| AppPersonal | APK debug compilado. 26 pruebas aprobadas. |
| Protocolo NFC | 11 pruebas aprobadas. |

Para repetir las suites y compilar desde la raíz, ejecuta `./scripts/validate-final.ps1`. El primer intento de Gradle agotó la memoria virtual del host mientras ejecutaba pruebas. Se completaron las tareas con un trabajador y un límite de memoria más bajo en la JVM de Gradle y en los ejecutores de prueba. El ajuste fue local a la ejecución.

Se enviaron un SOS y «Estoy al lado de un letrero morado gigante con forma de rayo.» desde AppAsistente en el emulador. La prueba usó Fastify en loopback, SQLite en memoria y un proveedor simulado. El proveedor recibió la descripción completa de 215 caracteres, propuso la zona y Fastify la aceptó. AppAsistente mostró el nombre completo en la respuesta de IA. AppPersonal mostró Entrada Perry's Stage bajo **Ubicación probable**, con la cita y la evidencia de catálogo. El endpoint de despacho para el organizador devolvió la misma zona con la confirmación humana vacía.

Se revisaron Inicio, Ayuda, Servicios y Perfil de AppAsistente e Inicio, Incidentes, Herramientas y Perfil de AppPersonal. El guardia asumió el mismo incidente mediante la API. La conversación pasó de `AUTO` a `STAFF` y AppAsistente mostró «Guardia a cargo», una respuesta con el nombre del guardia y la respuesta previa identificada como IA.

La prueba física en los Samsung sigue pendiente para NFC/HCE y el escaneo QR por cámara.

Se verificó por su proceso y el endpoint de salud que Fastify seguía usando `backend/data/final-6c282f8205304dd887269e831ed067bd.sqlite` en el puerto 3000. Se mantuvo en servicio durante la integración.

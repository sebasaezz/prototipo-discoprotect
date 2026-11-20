# AppPersonal · revisión visual de Fase 2

La línea base fue el APK previo al rediseño con el branding oficial. Las tres rondas usaron el emulador `a52_API_30` con Android 11 y Fastify H10 con una base desechable. La ventana normal fue de 360 dp. Los incidentes, usuarios, mensajes y entradas fueron ficticios. Las imágenes provienen de capturas del dispositivo Android virtual.

## Revisión de la línea base

Abrí las seis pantallas de configuración, lector, cola, detalle, chat y herramientas H9. El collage está en `.gradle/staff-ux/antes.png` y los originales en `.gradle/staff-ux/antes/`.

| Severidad | Hallazgo observado | Cambio |
| --- | --- | --- |
| Alta | La cola y el detalle competían por el espacio. La acción del guardia quedaba detrás de historial y campos administrativos. | Inicio muestra arriba la atención asignada por el servidor. Incidentes abre un detalle con prioridad, ubicación, situación y acciones. Los identificadores se consultan en «Ver datos del incidente». |
| Alta | El chat vertía conversación y controles de atención dentro del mismo desplazamiento. | La conversación tiene su propio desplazamiento y un compositor fijo. Las burbujas y el campo se comparten con AppAsistente. El propio guardia aparece como «Tú». |
| Media | Los diagnósticos ocupaban el espacio del lector. La entrada al catálogo H9 quedaba dentro de Incidentes. | NFC tiene acceso desde Inicio y Herramientas. Herramientas abre individualmente entradas, recinto y búsqueda. Perfil agrupa diagnósticos, conexión, cuenta y evento. |

## Primera implementación y ajuste

Abrí la primera ronda en `.gradle/staff-ux/revision-1/`. Incluye Inicio sin sesión y sin asignación, el P0 asignado, la cola con P1, detalle, conversación, teclado, borrador al reabrir, Herramientas, NFC, entradas, recinto, búsqueda, Perfil y configuración.

| Severidad | Hallazgo observado | Corrección aplicada |
| --- | --- | --- |
| Media | En Inicio, la tarjeta asignada repetía el nombre del guardia y ocupaba espacio equivalente a varias filas. Las demás alertas quedaban fuera de la primera vista. | Compacté los indicadores y el resumen de la tarjeta. Las acciones se acomodan al ancho y los tiempos largos muestran horas y minutos. Las tarjetas distinguen P0 y P1 con etiqueta y color. |
| Media | Ante una consulta fallida, el espacio de asignación podía sugerir que se había confirmado una cola vacía. | Diferencié una cola vacía confirmada de un estado todavía desconocido. Los datos anteriores se identifican como última consulta disponible. |
| Baja | El lector repetía «Lector NFC» y las opciones de una línea de Herramientas se alineaban arriba dentro de su superficie. | Dejé las instrucciones en el cuerpo del lector y centré las opciones con el indicador de navegación de AppAsistente. |
| Media | A 320 dp con fuente al 135 %, la barra partía «Incidentes» y «Herramientas» a mitad de palabra. | Di más ancho a esos dos destinos en pantallas estrechas con fuente ampliada. Los cuatro destinos conservan sus etiquetas y áreas táctiles. |

## Segunda revisión en Android

Abrí las capturas corregidas de Inicio, cola, detalle P0, conversación, teclado, Herramientas y Perfil. A 360 dp aparecen juntos el P0 y el primer P1 en la cola, y el acceso a NFC se ve en Inicio junto a la atención asignada. Cuando el guardia sin asignación inicia sesión, el P0 sigue visible en el resumen del evento. En el detalle, la ubicación probable conserva su origen y «Iniciar atención» precede a la evidencia de despacho. En el chat de 360 dp, el teclado permite ver y usar el campo y «Enviar». Al salir y volver desde Inicio, se recupera el borrador.

El recorrido con Fastify confirmó el acceso por PIN de dos cuentas ficticias, el cierre de sesión, la carga del evento autorizado, la asignación del P0 y el acceso a su mismo caso desde Inicio e Incidentes. También se comprobaron los mensajes de asistente, IA y del guardia, el catálogo de recinto y los resultados de búsqueda por zona. El control de entradas recibió «Entrada vencida» al probar un código cuyo plazo ya había terminado. Con la vigencia de esa entrada de prueba actualizada en la base desechable, Fastify registró un ingreso y devolvió «Entrada ya utilizada» al repetir el código. Las tres respuestas quedaron capturadas.

En la atención P0, la acción «Iniciar atención» cambió el estado a «En atención». Un mensaje escrito y enviado desde el APK quedó en el hilo con autor «Tú». La revisión humana guardó «Entrada principal» como ubicación confirmada por el guardia y conservó la evidencia que originó la ubicación probable. Cerré el caso indicando un motivo y comprobé el estado «Cerrada» y que el chat conservaba los mensajes en modo de solo lectura.

En un P1 sin asignar comprobé que el guardia podía consultar el chat y que el campo de escritura aparecía tras confirmar «Asumir». El mismo caso quedó «Asignada» y la consulta de acompañantes desde el detalle devolvió «Sin acompañantes vinculados». La prueba usó una cuenta sin vínculos consentidos.

Los originales Android de la segunda ronda están en `.gradle/staff-ux/despues/`. `.gradle/staff-ux/estrecho-antes-del-ajuste.png` muestra el problema de las etiquetas con fuente ampliada.

La nueva captura `despues/21-estrecho-inicio.png` confirma que, a 320 dp con fuente al 135 %, las cuatro etiquetas quedan en una línea. «Continuar atención», el P0 y la ubicación confirmada conservan la jerarquía. La fila de NFC sigue accesible desde Herramientas. Abrí también `despues/24-estrecho-teclado.png` y confirmé que el campo Mensaje y el botón Enviar quedan encima del teclado en ese ancho.

El resumen comparativo está en `.gradle/staff-ux/resumen.png`. La descarga `.gradle/staff-ux/capturas-apppersonal.zip` reúne los originales de las tres rondas.

En el detalle ampliado, la misma cita puede aparecer en varias filas si Fastify la asocia a varias señales de riesgo. La prioridad y la ubicación efectiva aparecen al comienzo del detalle.

## Pruebas automatizadas

| Suite | Resultado |
| --- | --- |
| `:reader:testDebugUnitTest` | 26 aprobadas |
| `:emitter:testDebugUnitTest` | 51 aprobadas |
| `:protocol:test` | 11 aprobadas |
| `:reader:assembleDebug` y `:emitter:assembleDebug` | Ambos APK generados |
| `:reader:lintDebug` | 0 errores, 3 advertencias de manifiesto y SDK preexistentes |

Las pruebas del lector cubren selección de evento, expiración, borrado de datos de sesión, errores y resultados NFC simulados, descarte de respuestas tardías, escritura incierta, conflicto de versión y el resultado de una lectura cancelada al salir de la pantalla. AppAsistente compila y ejecuta sus pruebas con los mismos componentes compartidos. Los contratos HTTP, backend, SQLite y protocolo NFC conservan el código de H10.

APK del personal en `reader/build/outputs/apk/debug/reader-debug.apk`. APK del asistente en `emitter/build/outputs/apk/debug/emitter-debug.apk`.

## Verificación física pendiente

El emulador informa «Sin adaptador». La aplicación muestra ese estado en el lector y en Diagnósticos. La pantalla «Listo para leer» y la ficha encontrada requieren el intercambio de radio. El QR de cámara también requiere una prueba con una cámara y una entrada legible.

1. Instalar AppAsistente en el Samsung S22 y AppPersonal en el S25. Configurar un servidor demo accesible desde ambos, abrir un evento autorizado y activar la credencial NFC de la cuenta ficticia.
2. Abrir el lector desde Inicio, verificar el estado listo y acercar ambos teléfonos. Verificar primero nombre, alergias de muestra y contactos de emergencia. Pulsar «Nueva lectura» y repetir con una segunda credencial. Desactivar NFC para comprobar los mensajes y la recuperación.
3. Acercar la credencial en Inicio, Incidentes, Chat y Herramientas antes de abrir el lector. Comprobar que la ficha solo se resuelve al entrar al lector y que un resultado iniciado allí se descarta tras volver.
4. Abrir Control de entradas y capturar una entrada QR ficticia. Comprobar el ingreso y repetir el mismo código para verificar que el segundo intento indica «Entrada ya utilizada».

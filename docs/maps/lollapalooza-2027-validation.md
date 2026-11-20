# Validación del mapa y la ubicación SOS

## Compilación y pruebas

| Componente | Resultado |
| --- | --- |
| Fastify y SQLite | Compilación TypeScript y 59 pruebas aprobadas. Incluye autenticación, sesiones, traspaso de chat, inferencia de Perry’s Stage, despacho, QR, acompañantes y persistencia tras reinicio. |
| Mapa y ubicación REST | Pruebas de borde, huecos, coordenadas inválidas, puntos exteriores al polígono pero interiores al rectángulo, catálogo actualizado, puntos públicos, GPS exterior, permisos e idempotencia. |
| GestiónWeb | Compilación Vite y 19 pruebas aprobadas. |
| AppAsistente | APK debug compilado y 53 pruebas aprobadas, incluida la selección por polígono y la conservación del intento geográfico ante una respuesta incierta. |
| AppPersonal | APK debug compilado y 26 pruebas aprobadas. |
| Protocolo NFC | 11 pruebas JVM aprobadas. |

La regresión del chat incluyó transporte simulado de Responses y persistencia del análisis en despacho. La validación del mapa y el SOS se hizo sin llamadas pagadas a OpenAI. Gradle se ejecutó con un trabajador y memoria reducida tras detectar agotamiento de memoria virtual en el host.

## Verificación visual en Android

El AVD A52 ejecutó Android 11 (API 30). La vista normal usó 720 × 1600 px a 320 dpi, equivalentes a 360 dp de ancho. Se revisó además 640 × 1600 px a 320 dpi, equivalentes a 320 dp. Se usó una SQLite nueva en un puerto de loopback independiente de la demo activa.

- El mapa inicial mostró las calles, senderos y el contorno real del parque. Se acercó y se volvió a encuadrar. El marcador de Perry’s Stage abrió la ficha con su descripción y el rótulo **Marcador simulado**.
- La selección manual rechazó un punto exterior, admitió corregirlo dentro del recinto y conservó el punto confirmado al visitar otra pestaña. La ubicación siguió identificada como **Manual**.
- Se denegó el permiso y se comprobó el mensaje que permite continuar con selección manual. A 320 dp se desactivó temporalmente la ubicación del AVD y se verificó el aviso y la selección manual con sus controles visibles. En la prueba de GPS se entregó una señal GNSS virtual al AVD. AppAsistente obtuvo la posición por Android y mostró **GPS · ±5 m**. Con solo `ACCESS_COARSE_LOCATION`, el AVD devolvió ±2.000 m y una posición exterior. La app mostró la posición aproximada sin moverla al parque y conservó la alternativa manual. Estos recorridos prueban la integración del emulador.
- El modo **Simular ubicación en el recinto** se probó por separado usando un toque dentro del polígono. El punto se confirmó, quedó visible en Inicio y se envió con el SOS. El recibo y SQLite conservaron **DEMO**. La API del titular y el despacho autorizado devolvieron la misma posición completa. Un reintento idéntico devolvió el mismo incidente con HTTP 200 y el cambio de procedencia para la misma clave devolvió HTTP 409. La tabla mantuvo una sola ubicación.

La primera revisión encontró un cierre en un AVD que exponía OpenGL ES 2. Se implementó una vista interactiva básica para ese perfil y se completó el recorrido de MapLibre en el mismo AVD con OpenGL ES 3.1. También se separaron puntos simulados que se superponían, se redujo el tamaño visual de los marcadores y se movió el control de atribución fuera del texto. La carga con una red lenta se dejó continuar antes de mostrar el respaldo local. Se volvieron a revisar el mapa y los controles.

## Evidencia reproducible

Las capturas y la prueba REST y SQLite se guardaron fuera de Git en `.gradle/mapa-gps-v1/Lollapalooza-2027-evidencia.zip`. El archivo se limita a datos ficticios de la instancia de prueba. Los APK se generaron en `emitter/build/outputs/apk/debug/emitter-debug.apk` y `reader/build/outputs/apk/debug/reader-debug.apk`.

## Demo activa y límites

Fastify permaneció en el puerto 3000 con `backend/data/final-6c282f8205304dd887269e831ed067bd.sqlite`. Se comprobó salud, se generó un respaldo consistente en modo lectura y se aplicó la migración 015 dos veces sobre esa copia. La integridad y las claves foráneas quedaron correctas. Los recuentos de las 33 tablas de datos previas coincidieron. El esquema y los datos de la instancia activa quedaron intactos.

Quedan pendientes las pruebas de recepción GNSS, NFC/HCE y QR por cámara en Samsung. El permiso aproximado se comprobó concediendo solo `ACCESS_COARSE_LOCATION` en el AVD API 30. El diálogo que separa ubicación precisa y aproximada en Android 12 y posteriores requiere una verificación aparte.

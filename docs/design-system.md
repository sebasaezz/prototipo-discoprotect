# Design System

Discoprotect usa una interfaz clara, plana y centrada en la tarea. La marca y el rol encabezan ambas pantallas. Asistente prioriza disponibilidad e instrucciones de NFC. Personal prioriza estado de lectura, identidad y alergias. Los diagnósticos se muestran a petición. Usa bordes discretos y espacio entre bloques para mantener una jerarquía clara.

## Color

| Token | Valor | Aplicación |
| --- | --- | --- |
| Primary / onPrimary | #402078 / #FFFFFF | Marca, acciones principales. |
| Primary container / onPrimary container | #EDE3F8 / #402078 | Fondo lavanda del símbolo NFC. |
| Secondary | #AE7EDB | Lavanda de marca, reservado para superficie con texto oscuro. |
| Secondary accesible / onSecondary | #68448B / #FFFFFF | Acento interactivo. |
| Background / Surface | #FFFFFF / #F7F4FA | Fondo principal y secciones de apoyo. |
| On Surface / secundario | #241735 / #564965 | Texto principal y descriptivo. |
| Éxito | #EAF5EE / #205638 | Compatibilidad y ficha encontrada. |
| Información | #E9F0FA / #254C81 | Lectura en progreso. |
| Advertencia | #FFF1DC / #774600 | Función desactivada e identidad desconocida. |
| Error | #FCEBED / #832234 | Errores NFC y alergias. Acción crítica futura #9D2435 con blanco. |
| Contorno / divisor | #786C84 / #DDD5E5 | Controles esenciales y separadores de apoyo. |

## Tipografía, espaciado y formas

Usa la sans serif de Android (Roboto en Android estándar), con normal, seminegrita y negrita. Titulares 36/42, 28/34 y 24/30 sp; títulos 18/24; cuerpo 16/24 y 14/21; etiquetas 14/20 y 12/18. El identificador opaco usa monoespaciada seleccionable. Espaciado en 4, 8, 16, 24, 32 y 40 dp. Radios en 4, 8, 12, 16 y 24 dp. El contenido desplaza verticalmente y limita el ancho a 560 dp. El símbolo NFC usa trazos planos redondeados de 2 dp y acompaña un título legible.

## Accesibilidad y componentes

Los estados se identifican por texto y color. El estado NFC incluye una región de anuncio y los encabezados tienen semántica de título. Los botones tienen un mínimo de 48 dp. Los objetivos de contraste son 4.5:1 para texto normal y 3:1 para texto grande y contornos esenciales. El divisor claro es decorativo. Usa contenido que pueda envolver con fuentes grandes, escalar en sp y seguir siendo accesible por desplazamiento. Comprueba los destinos y diagnósticos con TalkBack en los Samsung.

El contraste calculado de los pares principales es 12.38:1 para blanco sobre morado, 5.45:1 para texto oscuro sobre lavanda y 15.43:1 para texto oscuro sobre la superficie. Los cuatro pares de estado superan 7:1. El contorno esencial sobre blanco alcanza 4.91:1.

Android define colores, tipografía, formas, espaciado y componentes compartidos en `android-ui/src/main/kotlin/cl/discoprotect/ui/Design.kt`. Las apps usan Material 3 en modo claro con los colores de marca. GestiónWeb reutilizará los valores como variables CSS y usará Roboto o Inter, controles con foco visible y estados con texto. La futura UI web conservará la jerarquía de tareas y la densidad apropiada a cada pantalla.

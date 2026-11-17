# Guion físico vigente · H7–H7.3

La aceptación está pendiente. Usa exclusivamente datos ficticios, una SQLite descartable y una LAN de desarrollo controlada. Este guion cubre el botón **SOS** único y el chat que se abre después del recibo. Para la evidencia previa, consulta el [checkpoint H7.3](checkpoints/h7-3-2026-11-17.md).

## Preparación

1. Sigue la [instalación rápida](../README.md#instalación-rápida). Prepara una SQLite nueva con `DATABASE_PATH` y `npm run db:seed`. Mantén `ALLOW_DEMO_RESET=false` y `DEMO_PURGE_ENABLED=false` para el recorrido habitual.
2. Conecta S22, S25 y el PC a la misma LAN. Instala los APK debug de la revisión que se va a aceptar. Configura el origen LAN de la API en ambos teléfonos.
3. Usa `antonia` en S22, `guardia` en S25 y `organizador` en GestiónWeb. Selecciona el mismo evento. Para una segunda sesión de personal usa el PIN privado de `guardia2`. En cada escenario anota el ID, el resultado y los equipos usados.
4. Arranca primero con IA desactivada. Para los casos conversacionales con proveedor, habilítala en una sesión de prueba autorizada, con relato ficticio y cuota acotada. Consulta [configuración](current-workflow.md#7-despliegue-local).

## Guion físico mínimo

| Caso | Acción | Resultado esperado |
| --- | --- | --- |
| Recibo y chat vacío | Mantén **SOS** durante 1,5 s en S22. Anota ID y hora antes de escribir. Repite el mecanismo accesible en otro caso. | El S22 confirma y abre el chat vacío del mismo ID, con el aviso de espera. S25 y web reciben un solo incidente por activación. |
| IA y despacho | En un SOS con IA habilitada, envía «hola», luego «No hay médicos y mi amigo no respira cerca del escenario», y añade una aclaración de ubicación. | El saludo y los mensajes elegibles reciben un turno visible de **Asistente IA**. La evidencia corroborada puede fijar P0 provisional y se conserva tras aclarar la ubicación. |
| Traspaso y cierre | En S25 abre el caso y elige **Asumir conversación** mientras haya un turno pendiente. Abre **Mi atención activa** y la pantalla de chat. Responde, corrige ubicación y prioridad con motivo, inicia atención y cierra con motivo. | El S22 recibe respuestas humanas y el traspaso descarta resultados IA en vuelo. AppPersonal y GestiónWeb muestran la auditoría y trasladan el caso al historial. El chat queda en solo lectura. |
| Ocupación y cancelación | Con un incidente asignado, intenta asumir otro con la misma cuenta. Cancela el primero en S22 y vuelve a intentar el segundo. Repite el cierre desde `ASSIGNED` en otro caso. | El guardia ocupado puede consultar la cola y solo puede asumir otro caso tras liberar la asignación. El S22 puede cancelar una solicitud asignada y el S25 puede cerrarla antes de iniciar atención. |
| Fallback, reconexión y reinicio | Desactiva IA y reinicia Fastify con la misma base de prueba. Abre un SOS, escribe en el chat, interrumpe y recupera Wi-Fi durante un envío. Reintenta si el resultado queda incierto. | El SOS se confirma. AppAsistente muestra el aviso de IA indisponible. El personal puede asumir y atender. El reintento conserva una sola copia por mensaje y el historial se recupera tras reiniciar. |
| Retiro y purga | Retira un incidente desde GestiónWeb con motivo. Solo en una copia descartable, habilita la purga y comprueba la vista previa, la confirmación y el recibo con respaldo para un caso y para **Limpiar solicitudes demo**. | El retiro conserva el caso y su auditoría. La purga elimina únicamente la selección confirmada tras el respaldo. El reintento de una creación eliminada recibe conflicto. |
| NFC y accesibilidad | Abre Identificación en S22 y el lector conectado en S25. Lee la credencial ficticia. Revisa SOS, interlocutores y prioridades con TalkBack y texto grande. | El lector abre la ficha autorizada y conserva la separación entre intercambio NFC y atención de incidentes. Las etiquetas permiten reconocer acciones, estados y prioridad. |

Para probar la restricción entre eventos se requieren dos eventos autorizados para la misma cuenta de guardia. El seed inicial crea un solo evento. La [prueba focalizada de backend](../backend/tests/administration.test.ts) cubre el caso con dos.

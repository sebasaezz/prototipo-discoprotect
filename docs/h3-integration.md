# Validar H3 en Windows y los Samsung

## A. Conectar AppAsistente

1. Prepara el backend siguiendo [H2 en Windows](h2-lan.md#preparar-el-servidor). Usa una base de demostración, `HOST=0.0.0.0`, un PIN ficticio de guardia y una red que permita el tráfico entre dispositivos. H3 usa la misma API y el mismo SQLite.
2. Instala AppAsistente debug en el Galaxy S22. Puedes conservar el AppPersonal H2 instalado en el Galaxy S25. Los [APK y comandos](../README.md#android) están en el README.
3. En el S22 abre **Conexión**. Escribe `http://<IPv4-del-PC>:3000`, toca **Comprobar conexión** e **Iniciar sesión como Antonia**. Deben aparecer el nombre y **Credencial NFC de Antonia verificada**. Toca **Ir a Mi ficha**.
4. Verifica los datos, la fuente y la versión en **Mi ficha**. Abre **Contactos** para comprobar las personas de emergencia. El nombre y la edad solo se consultan en H3.

## B. Editar la ficha y leerla en el S25

1. En **Mi ficha** toca **Editar ficha**. Cambia o agrega una alergia, por ejemplo «Látex». Guarda y anota la versión del mensaje **Cambios guardados en el servidor**.
2. En el S25, con el guardia autenticado para **Festival Demo**, abre el lector. Acerca el dorso de ambos teléfonos y comprueba la misma alergia y versión, con **Fuente: servidor**. Para esta prueba, la edición se hace en el S22 y el backend puede seguir ejecutándose durante todo el recorrido.
3. Prueba cambiar el grupo sanguíneo a **Sin registrar**, eliminar una alergia y editar una condición o medicamento. Los elementos de cada lista se guardan como textos independientes.

## C. Crear, editar y eliminar contactos

1. En el S22 abre **Contactos → Agregar contacto**. Escribe nombre, relación y teléfono ficticios. Guarda y comprueba la nueva versión.
2. Realiza una nueva lectura con el S25 y busca el contacto. En el S22 cambia su relación mediante **Editar** y comprueba la actualización en otra lectura.
3. En el S22 toca **Eliminar**. Cancela primero para comprobar que conservar el contacto es posible. Abre otra vez y toca **Confirmar eliminación**. La lista del S22 y una nueva lectura del S25 deben mostrar la eliminación y una versión mayor.

## D. Conflicto y respuesta incierta

1. En el S22 abre la edición de la ficha y cambia una alergia sin guardar.
2. Con una segunda sesión de demostración, cambia otro campo. Para esta prueba de concurrencia usa los [comandos de edición por API de H2](h2-lan.md#b-y-c-leer-dos-versiones-sin-reinstalar), adaptando el cuerpo para cambiar un campo distinto del borrador.
3. Pulsa **Guardar ficha** en el S22. Deben aparecer la advertencia de conflicto, la versión vigente y el borrador. Compara ambos. Elige **Cerrar borrador** o **Continuar editando con esta versión**. Tras continuar, verifica que se conserve el cambio remoto de los campos que dejaste intactos. Guarda explícitamente.
4. Si falla la conexión después de pulsar Guardar, usa **Cargar versión vigente** cuando vuelva el servidor. Si ya ves el cambio intentado, cierra el borrador. La app bloquea la repetición cuando identifica en la lista un nuevo contacto que coincide con el envío.

## E. Desconectar y recuperar

1. Detén Fastify mientras el S22 muestra **Identificación**. El S22 sigue emitiendo el ID fijo de Antonia. En el S25, comprueba que el diagnóstico recibe el ID y que el lector conectado informa del fallo de consulta. Si necesitas comprobar la ficha ficticia local, elige voluntariamente la demo local del S25.
2. En el S22 consulta o intenta guardar. Comprueba que el error aparece y que la pantalla no presenta un borrador como guardado. Al recuperar el servidor, actualiza la ficha y revisa cualquier operación incierta.
3. Si la sesión venció, vuelve a iniciar sesión como Antonia. Si la dirección o credencial no coincide, revisa que estés usando la base de demostración de Discoprotect. Para problemas de LAN o firewall, sigue el [diagnóstico de H2](h2-lan.md#preparar-el-servidor).
4. Repite una lectura H2 con guardia y evento seleccionado. Conserva el mismo AID e identificador, con demo local explícita.

HTTP local se usa solo en debug y con datos ficticios. El inicio de sesión sin PIN es una facilidad académica. Durante la demo, el S22 debe estar despierto y desbloqueado si el hardware o Secure NFC lo requieren.

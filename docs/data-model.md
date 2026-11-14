# Modelo de datos H1

Los identificadores se tratan como cadenas opacas, los tiempos se guardan en UTC y SQLite valida claves foráneas en cada conexión. Las migraciones se aplican en orden y se registran en `schema_migrations`. Se usa WAL con un tiempo de espera de 5 segundos para escritores. Las escrituras de ficha y contacto pasan por transacciones inmediatas.

| Entidad | Datos y restricciones |
| --- | --- |
| Person | ID estable, clave demo única, nombre ficticio, edad opcional, creación y actualización. |
| EmergencyCard | Una por persona, grupo sanguíneo declarado, listas JSON de alergias, condiciones y medicamentos, fecha de actualización y versión entera. |
| EmergencyContact | Varios por ficha, con ID, nombre, relación y teléfono ficticio. |
| NfcCredential | Una por asistente, 16 bytes únicos como BLOB, indicador activo y creación. El transporte HTTP usa hexadecimal. |
| Event | ID, nombre, recinto, inicio, término y estado. El estado `ACTIVE` de la demo es explícito. |
| EventMembership | Una membresía por persona y evento. Roles `ATTENDEE`, `STAFF` y `ORGANIZER`. |
| DemoSession | Hash SHA-256 de un token aleatorio, cuenta, rol y vencimiento. La base guarda el hash; el valor Bearer se entrega al crear la sesión. |

La versión representa la ficha y sus contactos. Toda mutación exitosa aumenta la versión y actualiza la fecha. La resolución NFC devuelve ambos en una sola lectura consistente. El seed inserta los cinco perfiles y tres fichas ficticias. Las ejecuciones posteriores preservan ediciones y eliminaciones de contactos. La restauración reinicia las cinco cuentas de prueba, el evento demo y los registros relacionados por claves foráneas, incluidas las sesiones.

## Ubicación futura

`Venue` identificará el recinto, `FloorPlan` un plano o piso y `Zone` un sector seleccionable. `IncidentLocation` referenciará una zona cuando se conozca y podrá llevar coordenadas relativas normalizadas, texto original, momento y fuente de la ubicación. El primer flujo usará selección manual de zona. Los planos y la localización forman parte de futuros hitos.

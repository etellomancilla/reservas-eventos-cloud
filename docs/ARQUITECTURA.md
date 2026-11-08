# Diseño del proyecto

[← README](../README.md)

## Servicios y datos

| Servicio | Responsabilidad | Persistencia |
|---|---|---|
| `ms-reservas` | Eventos, cupos, reservas, pago simulado, cancelación y estadísticas | Esquema PostgreSQL `reservas` |
| `ms-tickets` | PDF, EFS, S3, descargas e historial | Esquema PostgreSQL `tickets` |

Tickets consulta reservas y eventos mediante REST; no accede a las tablas del otro servicio. Los esquemas comparten una instancia PostgreSQL para simplificar la evaluación.

## Flujo de generación

1. Recibir `reservaId`, `titular` y `asiento`.
2. Consultar reserva/evento; rechazar reserva cancelada o duplicada.
3. Confirmar el registro `PENDIENTE_S3` en la base de datos.
4. Generar PDF en `/app/efs/evento<UUID>/<usuarioId>/ticket<UUID>.pdf`.
5. Calcular SHA-256 y subir **ese archivo** con `RequestBody.fromFile`.
6. Solo después de confirmar S3, marcar `SINCRONIZADO`.

S3 usa la misma clave relativa, sin `/` inicial. La respuesta contiene `id`, `s3Key`, `estadoArchivo` y `sha256`.

## Estados recuperables

| Estado | Significado | Acción |
|---|---|---|
| `PENDIENTE_S3` | Subida todavía no confirmada | `POST /api/tickets/{id}/s3` |
| `SINCRONIZADO` | PDF disponible en S3 | Descargar o modificar |
| `BORRANDO` | Eliminación iniciada, posiblemente incompleta | Reintentar `DELETE /api/tickets/{id}` |
| `ELIMINADO` | Archivo eliminado, metadatos conservados | Consultar historial |

Si S3 falla, se devuelve `503` con `ticketId`. Los estados permiten recuperar fallos entre PostgreSQL y S3, que no comparten transacción.

La modificación invalida el temporal anterior y regenera el PDF. Mientras está pendiente, descargar devuelve `409` para no entregar contenido antiguo como actual. El borrado es idempotente.

## Persistencia

- PostgreSQL utiliza el volumen Docker `reservas-db`, sobre EBS de EC2; no se elimina al desplegar.
- Los PDF sincronizados permanecen en EFS 24 horas; una tarea horaria limpia hasta 100 temporales por ejecución.
- Los pendientes no se limpian automáticamente.
- Las descargas siempre leen S3, aunque el temporal ya no exista.
- El SHA-256 debe coincidir con el registrado; un archivo alterado produce `502`.

## Reglas

- Una reserva representa una entrada y admite un solo ticket, incluso si después se borra el PDF.
- `RESERVADA` y `PAGADA` ocupan cupo; `CANCELADA` lo libera.
- Las operaciones de cupos bloquean la fila del evento para evitar sobreventa concurrente.
- Una cancelada no se reactiva; una pagada no vuelve a pendiente.
- Ingresos = suma del precio pagado de reservas actualmente `PAGADA`.
- Cancelar deja de contabilizar ingresos; no representa una devolución bancaria real.
- Eliminar un PDF no elimina una reserva ni cambia ventas.
- El PDF guarda el estado de reserva al emitirlo. Pagar después no actualiza automáticamente ese texto.
- `usuarioId` identifica al participante; no hay autenticación ni pasarela de pago.

## Consultas y errores

Historial con índices evento/fecha y usuario/fecha, filtros combinables, `page >= 0` y `size` entre 1 y 100. `desde` inclusivo; `hasta` exclusivo. Incluye eliminados si no se indica estado.

ZIP por evento, usuario o ambos: solo PDF sincronizados, máximo 200. Superar ese límite produce `413`, sin truncar silenciosamente.

| Código | Caso |
|---|---|
| 400 | Formato o datos inválidos |
| 404 | Registro inexistente o archivo eliminado |
| 409 | Sin cupos, duplicado o conflicto de estado |
| 413 | ZIP de más de 200 tickets |
| 502 | PDF no coincide con SHA-256 |
| 503 | Almacenamiento o Reservas no disponible |

Este despliegue usa una réplica de Tickets y serializa sus operaciones de archivos. Varias réplicas requerirían coordinación distribuida.

# Pruebas y demostración con Postman

[← README](../README.md) · [Instalación AWS](../GUIA_EVALUACION.md)

## 1. Importar

En Postman Desktop pulsa **Import**:

1. Importa [la colección](../postman/Reservas-Tickets.postman_collection.json).
2. Importa el entorno [Local](../postman/Local.postman_environment.json) o [AWS](../postman/AWS.postman_environment.json).
3. Para AWS sustituye `REEMPLAZAR_IP_ELASTICA` en ambas URL del entorno:

| Variable | Formato |
|---|---|
| `reservasUrl` | `http://TU_IP_ELASTICA:8081` |
| `ticketsUrl` | `http://TU_IP_ELASTICA:8080` |

Selecciona el entorno arriba a la derecha. La colección guarda IDs automáticamente y crea una fecha futura para el evento.

## 2. Ejecutar en orden

Para ensayar usa **Run collection**. Para el video envía las solicitudes individualmente y alterna con S3 cuando corresponda. Cada ejecución completa crea un evento y usuarios nuevos.

| Solicitudes | Demostración | Resultado |
|---|---|---|
| 01–02 | Salud | Ambos `UP` |
| 03 | Evento con capacidad 10 y precio 15000 | `201` e ID |
| 04–09 | Tres reservas y tres tickets | Dos pagadas, una pendiente; PDF sincronizados |
| 10 | Subida explícita | Misma clave y mismo ticket |
| 11 | Descargar PDF | Archivo personalizado e íntegro |
| 12–13 | ZIP por evento y usuario | 3 PDF del evento; 2 del primer usuario |
| 14–15 | Editar y descargar | Misma clave, datos y hash nuevos |
| 16–17 | Historial filtrado | 2 resultados; luego 0 con otro filtro |
| 18 | Estadísticas | 3 activas, 2 vendidas, 1 pendiente, ingresos 30000 |
| 19–20 | Duplicado e inválido | `409` y `400` |
| 21–23 | Borrar y consultar historial | `204`, descarga `404`, registro `ELIMINADO` |
| 24 | Estadísticas después del borrado | Ingresos permanecen en 30000 |
| 25–26 | Pagar la reserva pendiente | 3 ventas, ingresos 45000 |

Las solicitudes incluyen comprobaciones en **Tests / Post-response**. Revisa **Test Results**.

## 3. Mostrar S3 después de Postman

### Generación — solicitudes 05, 07 y 09

1. Muestra `estadoArchivo: SINCRONIZADO` y `s3Key` en la respuesta.
2. Abre AWS → S3 → tu bucket → **Refresh**.
3. Navega al evento y usuario correspondientes.
4. Muestra `ticket<UUID>.pdf`, tamaño y `Content-Type: application/pdf`.

Una sola llamada generó el PDF en EFS y lo subió automáticamente. La solicitud 10 demuestra el endpoint de subida explícita; no es necesaria para la subida automática.

### Modificación — solicitud 14

1. Muestra titular y asiento nuevos.
2. Actualiza S3: misma clave, fecha de modificación nueva.
3. Descarga con solicitud 15 y abre el PDF: debe indicar **Ana Pérez González / B-12**.

### Eliminación — solicitud 21

1. Muestra `204` en Postman.
2. Actualiza S3: desaparece ese PDF, los demás permanecen.
3. Solicitud 22: descargar devuelve `404`.
4. Solicitud 23: historial conserva el registro `ELIMINADO`.

## 4. Guardar y abrir PDF/ZIP

En la respuesta usa **Save response / Save to a file**. Según la versión también puede aparecer **Send and Download**.

- Guarda PDF como `.pdf` y ábrelo.
- Guarda ZIP como `.zip`, descomprímelo y abre sus PDF.
- Mostrar bytes en Postman no demuestra por sí solo que el archivo funcione.

## 5. Integridad

Compara el encabezado **`X-Checksum-SHA256`** con el hash del archivo guardado.

macOS:

```bash
shasum -a 256 ~/Downloads/ticket.pdf
```

Windows PowerShell:

```powershell
Get-FileHash .\ticket.pdf -Algorithm SHA256
```

Compara la misma versión; editar cambia el hash. No uses el ETag de S3 como sustituto de SHA-256.

## 6. Persistencia tras otro despliegue

1. Termina la colección y conserva el `eventoId`.
2. Haz otro push a `main` y espera deploy verde.
3. Repite **solo las solicitudes 26 y 23**.
4. Deben mantenerse estadísticas e historial.

No repitas la solicitud 03 antes de esta prueba: reemplaza el evento guardado.

## 7. Si S3 falla

La recuperación está cubierta por pruebas automatizadas. Ante un `503` real:

1. Conserva el `ticketId` del error.
2. Corrige permisos o conexión S3.
3. Ejecuta `POST {{ticketsUrl}}/api/tickets/TICKET_ID/s3`.
4. Comprueba `SINCRONIZADO` y el objeto en S3.

No generes otro ticket para la misma reserva: se rechaza con `409` para evitar duplicados.

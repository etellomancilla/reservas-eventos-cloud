# Validación realizada

[← README](../README.md)

Fecha: **8 de noviembre de 2026**.

## Resultado local

| Comprobación | Resultado |
|---|---|
| Compilación limpia Maven `clean verify` | Correcta; ambos JAR generados |
| Pruebas automatizadas de Reservas | 8 aprobadas |
| Pruebas automatizadas de Tickets | 13 aprobadas |
| Colección Postman ejecutada con Newman | 26 solicitudes, 43 aserciones, 0 fallos |
| Comunicación HTTP entre los dos servicios | Correcta |
| Reinicio de ambos procesos | Estadísticas, 3 registros históricos y 2 PDF conservados e íntegros |
| Scripts de despliegue | Sintaxis Bash revisada |
| Workflow y políticas | YAML/JSON revisados |

La compilación se ejecutó con Maven 3.9.14 y JDK 24, generando bytecode para Java 21. Los Dockerfiles usan JDK/JRE 21.

## Cobertura

Las pruebas comprueban PDF real y personalizado, copia desde el temporal, SHA-256, edición en la misma clave, filtros/paginación, ZIP, eliminación con historial, reintento tras fallo S3, fallo de edición/borrado, retención, validación HTTP, cupos concurrentes, transición de estados e ingresos.

La colección se ejecutó contra procesos independientes de Reservas y Tickets. Se verificó además que, al detenerlos y volver a iniciarlos con los JAR finales, los datos y archivos previos continuaran disponibles.

## Qué todavía debe verificarse en tu cuenta

Estas pruebas locales utilizan **H2 y almacenamiento S3 simulado**. Las pruebas automatizadas simulan S3 y el cliente HTTP cuando corresponde.

No se han creado recursos ni ejecutado un despliegue en tu cuenta AWS. Tampoco se han construido las imágenes con un motor Docker local, porque no está instalado en este equipo.

Antes de entregar debes comprobar:

- [ ] GitHub Actions construye y publica ambas imágenes.
- [ ] El job deploy termina correctamente en EC2.
- [ ] PostgreSQL conserva datos después de un redespliegue.
- [ ] EFS está montado en EC2 y dentro del contenedor.
- [ ] Los PDF reales aparecen, se actualizan y se eliminan en S3.
- [ ] Postman funciona usando la IP elástica.
- [ ] La grabación demuestra los requisitos de la pauta.

Sigue [la guía AWS](../GUIA_EVALUACION.md) para completar estas verificaciones. La evidencia local no sustituye la demostración cloud.

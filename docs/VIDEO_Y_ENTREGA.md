# Video y entrega

[← README](../README.md)

## Preparación

- Crea una reunión Teams con tu compañero y verifica permiso para grabar.
- Abre GitHub Actions, Docker Hub, EC2, EFS, S3, Session Manager y Postman.
- Selecciona el entorno AWS en Postman y ensaya antes de grabar.

## Guion sugerido — 12 a 15 minutos

| Parte | Qué mostrar |
|---|---|
| Presentación | Caso, responsabilidades y arquitectura |
| CI/CD | Push main, pruebas, publicación de imágenes y deploy verde |
| Infraestructura | EC2 con IP elástica, EFS y mount target |
| Doble montaje | `df -h` en EC2 y dentro del contenedor; `docker inspect` |
| Funciones | Generar, subir, descargar, modificar, historial, estadísticas y borrar |
| S3 | Actualizar y mostrar resultados después de generar, editar y borrar |
| Persistencia | Otro despliegue y consulta de datos anteriores |
| Cierre | Repositorio y documentación |

Ajusta la duración si el docente establece otro límite.

## Comandos para grabar

En EC2:

```bash
df -h
df -hT /mnt/efs
sudo docker ps
sudo docker inspect ms-tickets --format '{{json .Mounts}}'
sudo docker inspect --format '{{.Name}} {{.Config.Image}} {{index .Config.Labels "app.commit"}}' ms-reservas ms-tickets
sudo docker exec -it ms-tickets sh
```

Dentro del contenedor:

```bash
df -h
df -h /app/efs
grep ' /app/efs ' /proc/mounts
find /app/efs -type f
exit
```

El SHA de la imagen y `app.commit` deben coincidir con el commit de Actions.

## Pauta y evidencia

| Criterio | Evidencia |
|---|---|
| EFS funcional | Recurso, mount target, ambos `df -h`, bind mount y PDF |
| Subida automática | Generar en Postman y mostrar S3 por evento/usuario |
| Modificación | Misma clave S3 y PDF con datos nuevos |
| Descarga íntegra | Abrir PDF/ZIP y comparar SHA-256 |
| Historial | Consultas por evento, usuario, fechas y estado |
| Pipeline | Push → pruebas → Docker Hub → EC2 saludable |
| Video claro | Explicar cada requisito mientras se demuestra |

## Entregables

1. Enlace a grabación Teams accesible para el docente.
2. Enlace al repositorio GitHub.
3. ZIP con informe, colección Postman, capturas y enlaces.

`documentacion.zip` contiene la documentación técnica y colección. **Antes de entregar**, agrega tus evidencias reales en `docs/evidencias/` y completa `docs/ENTREGA.md`. No incluyas claves, tokens ni `app.env`.

Actualiza el ZIP desde la raíz:

```bash
python3 scripts/package-docs.py
```

Publica los enlaces en el AVA junto al ZIP. Comprueba que el docente pueda abrir tanto el repositorio como la grabación.

## Después de la revisión

Conserva los recursos mientras el docente los necesite. Al terminar, elimina los recursos de este laboratorio: EC2, EFS y mount targets, objetos/bucket S3, IP elástica (liberarla), red y permisos dedicados. Elimina también volúmenes EBS o snapshots retenidos cuando ya no necesites los datos.

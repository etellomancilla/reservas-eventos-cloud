#!/usr/bin/env bash
# Ejecutado como root por SSM. Uso: bash deploy.sh USUARIO_DOCKER SHA_COMMIT
set -euo pipefail
docker_user=${1:?Falta usuario Docker Hub}
image_tag=${2:?Falta SHA del commit}
[[ "$docker_user" =~ ^[a-z0-9][a-z0-9_-]*$ ]]
[[ "$image_tag" =~ ^[0-9a-f]{40}$ ]]
test -f /opt/reservas/app.env

# No basta con que exista la carpeta: debe estar sobre NFS/EFS.
mountpoint -q /mnt/efs
[[ "$(findmnt -n -o FSTYPE -T /mnt/efs)" == nfs* ]]
test -d /mnt/efs/tickets
docker network inspect reservas-net >/dev/null 2>&1 || docker network create reservas-net
docker volume inspect reservas-db >/dev/null 2>&1 || docker volume create reservas-db

# Descargar ambas imágenes antes de detener la versión anterior.
docker pull "$docker_user/ms-reservas:$image_tag"
docker pull "$docker_user/ms-tickets:$image_tag"
if ! docker container inspect reservas-db >/dev/null 2>&1; then
  docker run -d --name reservas-db --restart unless-stopped \
    --network reservas-net --env-file /opt/reservas/app.env \
    --mount type=volume,source=reservas-db,target=/var/lib/postgresql/data \
    postgres:16
else
  docker start reservas-db >/dev/null
fi

db_ready=false
for attempt in $(seq 1 60); do
  if docker exec reservas-db pg_isready -U reservas -d reservas >/dev/null 2>&1; then
    db_ready=true
    break
  fi
  sleep 2
done
if [[ "$db_ready" != true ]]; then
  docker logs --tail 60 reservas-db
  exit 1
fi
docker exec reservas-db psql -U reservas -d reservas -v ON_ERROR_STOP=1 \
  -c 'CREATE SCHEMA IF NOT EXISTS reservas; CREATE SCHEMA IF NOT EXISTS tickets;'

remove_previous() {
  if docker container inspect "$1" >/dev/null 2>&1; then
    docker rm -f "$1"
  fi
}
remove_previous ms-reservas
docker run -d --name ms-reservas --restart unless-stopped \
  --network reservas-net -p 8081:8080 \
  --env-file /opt/reservas/app.env \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://reservas-db:5432/reservas \
  -e DB_SCHEMA=reservas \
  -e JAVA_TOOL_OPTIONS=-Xmx512m \
  --label "app.commit=$image_tag" \
  "$docker_user/ms-reservas:$image_tag"

wait_app() {
  local port=$1 name=$2
  for attempt in $(seq 1 90); do
    if curl -fsS --max-time 3 "http://localhost:$port/actuator/health" | grep -q '"status":"UP"'; then
      return 0
    fi
    sleep 2
  done
  docker logs --tail 80 "$name"
  return 1
}
wait_app 8081 ms-reservas

remove_previous ms-tickets
docker run -d --name ms-tickets --restart unless-stopped \
  --network reservas-net -p 8080:8080 \
  --env-file /opt/reservas/app.env \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://reservas-db:5432/reservas \
  -e DB_SCHEMA=tickets \
  -e RESERVAS_URL=http://ms-reservas:8080 \
  -e EFS_PATH=/app/efs \
  -e JAVA_TOOL_OPTIONS=-Xmx512m \
  --mount type=bind,source=/mnt/efs/tickets,target=/app/efs \
  --label "app.commit=$image_tag" \
  "$docker_user/ms-tickets:$image_tag"
wait_app 8080 ms-tickets

df -h /mnt/efs
docker exec ms-tickets df -h /app/efs
docker exec ms-tickets sh -c 'test -w /app/efs && grep " /app/efs " /proc/mounts'
docker inspect --format '{{.Name}} {{.Config.Image}} {{index .Config.Labels "app.commit"}}' ms-reservas ms-tickets

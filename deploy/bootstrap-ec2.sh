#!/usr/bin/env bash
# Amazon Linux 2023. Preparación inicial; el arranque de las aplicaciones lo hace Actions.
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo 'Ejecutar con sudo.'; exit 1; }
efs_id=${1:?Uso: bootstrap-ec2.sh fs-ID REGION BUCKET}
region=${2:?Falta región}
bucket=${3:?Falta bucket}
[[ "$efs_id" =~ ^fs-[a-f0-9]+$ ]]
[[ "$region" =~ ^[a-z]{2}-[a-z]+-[0-9]+$ ]]
[[ "$bucket" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]]
dnf install -y docker amazon-efs-utils openssl
systemctl enable --now amazon-ssm-agent
mkdir -p /mnt/efs
if ! mountpoint -q /mnt/efs; then
  mount -t efs -o tls "$efs_id:/" /mnt/efs
fi
[[ "$(findmnt -n -o FSTYPE -T /mnt/efs)" == nfs* ]]
if ! awk '$2=="/mnt/efs" {found=1} END {exit !found}' /etc/fstab; then
  printf '%s:/ /mnt/efs efs _netdev,tls 0 0\n' "$efs_id" >> /etc/fstab
fi
mkdir -p /mnt/efs/tickets
chown 10001:10001 /mnt/efs/tickets
chmod 750 /mnt/efs/tickets
mkdir -p /etc/systemd/system/docker.service.d
cat > /etc/systemd/system/docker.service.d/efs.conf <<'EOF'
[Unit]
RequiresMountsFor=/mnt/efs
After=network-online.target
Wants=network-online.target
EOF
systemctl daemon-reload
systemctl enable --now docker
install -d -m 700 /opt/reservas
if [[ ! -f /opt/reservas/app.env ]]; then
  umask 077
  db_password=$(openssl rand -hex 24)
  cat > /opt/reservas/app.env <<EOF
AWS_REGION=$region
TICKETS_BUCKET=$bucket
POSTGRES_DB=reservas
POSTGRES_USER=reservas
POSTGRES_PASSWORD=$db_password
SPRING_DATASOURCE_USERNAME=reservas
SPRING_DATASOURCE_PASSWORD=$db_password
EOF
  unset db_password
fi
chmod 600 /opt/reservas/app.env
df -h /mnt/efs
echo 'EC2 preparada. Configura GitHub Actions y haz push a main para desplegar.'

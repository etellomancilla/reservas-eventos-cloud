#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for service in ms-reservas ms-tickets; do
  test -f "$service/target/$service-1.0.0.jar" || {
    echo 'Primero ejecuta: ./mvnw clean verify'; exit 1;
  }
done
mkdir -p .local
java -jar ms-reservas/target/ms-reservas-1.0.0.jar --spring.profiles.active=local > .local/reservas.log 2>&1 &
reservas_pid=$!
java -jar ms-tickets/target/ms-tickets-1.0.0.jar --spring.profiles.active=local > .local/tickets.log 2>&1 &
tickets_pid=$!
cleanup() { kill "$reservas_pid" "$tickets_pid" 2>/dev/null || true; }
trap cleanup EXIT
trap 'exit 130' INT TERM
for attempt in $(seq 1 60); do
  if curl -fsS http://localhost:8081/actuator/health >/dev/null 2>&1 && curl -fsS http://localhost:8080/actuator/health >/dev/null 2>&1; then
    echo 'Reservas: http://localhost:8081 | Tickets: http://localhost:8080'
    echo 'Modo local: H2 y S3 simulado. No demuestra EFS/S3 reales. Ctrl+C para detener.'
    wait "$reservas_pid" "$tickets_pid"
    exit $?
  fi
  if ! kill -0 "$reservas_pid" 2>/dev/null || ! kill -0 "$tickets_pid" 2>/dev/null; then
    echo 'No se pudo iniciar. Revisa .local/reservas.log y .local/tickets.log'; exit 1
  fi
  sleep 2
done
echo 'No respondieron ambos servicios; revisa .local/*.log'
exit 1

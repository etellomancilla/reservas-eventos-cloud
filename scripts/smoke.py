#!/usr/bin/env python3
"""Prueba REST contra servicios levantados. Crea un evento propio por ejecución."""
import hashlib
import io
import json
import os
import urllib.request
import urllib.error
import zipfile
from datetime import datetime, timedelta, timezone

RESERVAS = os.getenv("RESERVAS_URL", "http://localhost:8081")
TICKETS = os.getenv("TICKETS_URL", "http://localhost:8080")

def request(base, method, path, body=None, expected=200, binary=False):
    req = urllib.request.Request(base + path, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json"})
    try:
        response = urllib.request.urlopen(req, timeout=45)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        assert response.status == expected, (method, path, response.status, raw.decode(errors="replace"))
        return raw if binary else json.loads(raw) if raw else None

future = (datetime.now(timezone.utc) + timedelta(days=30)).isoformat()
event = request(RESERVAS, "POST", "/api/eventos", {"nombre": "Demo automática", "fecha": future, "capacidad": 10, "precio": 15000}, 201)
tickets = []
for user, state in [("usuarioX", "PAGADA"), ("usuarioX", "PAGADA"), ("usuarioY", "RESERVADA")]:
    reservation = request(RESERVAS, "POST", "/api/reservas", {"eventoId": event["id"], "usuarioId": user, "estado": state}, 201)
    ticket = request(TICKETS, "POST", "/api/tickets", {"reservaId": reservation["id"], "titular": "Ana Pérez", "asiento": f"A-{len(tickets)+1}"}, 201)
    tickets.append(ticket)
    assert ticket["estadoArchivo"] == "SINCRONIZADO"
    data = request(TICKETS, "GET", f'/api/tickets/{ticket["id"]}/archivo', binary=True)
    assert data.startswith(b"%PDF-")
    assert hashlib.sha256(data).hexdigest() == ticket["sha256"]
first = tickets[0]
request(TICKETS, "POST", f'/api/tickets/{first["id"]}/s3')
updated = request(TICKETS, "PUT", f'/api/tickets/{first["id"]}', {"titular": "Ana González", "asiento": "B-10"})
assert updated["s3Key"] == first["s3Key"] and updated["sha256"] != first["sha256"]
for query, expected in [(f'eventoId={event["id"]}', 3), (f'eventoId={event["id"]}&usuarioId=usuarioX', 2)]:
    data = request(TICKETS, "GET", "/api/tickets/descarga?" + query, binary=True)
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        assert len(archive.namelist()) == expected and archive.testzip() is None
history = request(TICKETS, "GET", f'/api/tickets?eventoId={event["id"]}&usuarioId=usuarioX')
assert history["totalElements"] == 2
stats = request(RESERVAS, "GET", f'/api/eventos/{event["id"]}/estadisticas')
assert (stats["reservasActivas"], stats["vendidas"], stats["pendientes"], stats["ingresos"]) == (3, 2, 1, 30000)
request(TICKETS, "DELETE", f'/api/tickets/{first["id"]}', expected=204)
request(TICKETS, "GET", f'/api/tickets/{first["id"]}/archivo', expected=404)
deleted = request(TICKETS, "GET", f'/api/tickets?eventoId={event["id"]}&estadoArchivo=ELIMINADO')
assert deleted["totalElements"] == 1
print(json.dumps({"resultado": "OK", "eventoId": event["id"], "tickets": [t["id"] for t in tickets], "estadisticas": stats}, indent=2))

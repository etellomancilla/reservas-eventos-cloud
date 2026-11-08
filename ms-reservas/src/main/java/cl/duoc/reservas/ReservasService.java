package cl.duoc.reservas;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReservasService {
  @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
  private final EventoRepository eventos;
  private final ReservaRepository reservas;

  public ReservasService(EventoRepository eventos, ReservaRepository reservas) {
    this.eventos = eventos;
    this.reservas = reservas;
  }

  public Evento evento(UUID id) {
    return eventos.findById(id).orElseThrow(() -> error(404, "Evento inexistente"));
  }

  public Reserva reserva(UUID id) {
    return reservas.findById(id).orElseThrow(() -> error(404, "Reserva inexistente"));
  }

  public Evento crearEvento(Api.NuevoEvento r) {
    return eventos.save(new Evento(r.nombre(), r.fecha(), r.capacidad(), r.precio()));
  }

  @Transactional
  public Reserva reservar(Api.NuevaReserva r) {
    Evento e = eventos.lockById(r.eventoId()).orElseThrow(() -> error(404, "Evento inexistente"));
    if (r.estado() == Reserva.Estado.CANCELADA)
      throw error(400, "Una reserva nueva no puede estar cancelada");
    if (!e.fecha.isAfter(java.time.Instant.now())) throw error(409, "El evento ya comenzó");
    if (reservas.countByEventoIdAndEstadoNot(e.id, Reserva.Estado.CANCELADA) >= e.capacidad)
      throw error(409, "No hay cupos disponibles");
    return reservas.save(new Reserva(e.id, r.usuarioId(), r.estado(), e.precio));
  }

  @Transactional
  public Reserva estado(UUID id, Api.CambioEstado body) {
    Reserva r = reserva(id);
    eventos.lockById(r.eventoId).orElseThrow(() -> error(404, "Evento inexistente"));
    entityManager.refresh(r);
    // El bloqueo del evento serializa las operaciones de cupos.
    if (r.estado == body.estado()) return r;
    if (r.estado == Reserva.Estado.CANCELADA || body.estado() == Reserva.Estado.RESERVADA)
      throw error(409, "Transición de estado no permitida");
    r.estado = body.estado();
    if (r.estado == Reserva.Estado.PAGADA) r.precioPagado = r.precioReserva;
    return reservas.save(r);
  }

  @Transactional
  public Api.Estadisticas estadisticas(UUID id) {
    eventos.lockById(id).orElseThrow(() -> error(404, "Evento inexistente"));
    long p = reservas.countByEventoIdAndEstado(id, Reserva.Estado.RESERVADA);
    long v = reservas.countByEventoIdAndEstado(id, Reserva.Estado.PAGADA);
    long c = reservas.countByEventoIdAndEstado(id, Reserva.Estado.CANCELADA);
    return new Api.Estadisticas(id, p + v, p, v, c, reservas.ingresos(id));
  }

  static ResponseStatusException error(int status, String message) {
    return new ResponseStatusException(HttpStatus.valueOf(status), message);
  }
}

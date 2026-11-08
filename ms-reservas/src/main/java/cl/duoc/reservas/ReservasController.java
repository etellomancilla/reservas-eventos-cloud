package cl.duoc.reservas;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ReservasController {
  private final ReservasService service;

  public ReservasController(ReservasService service) {
    this.service = service;
  }

  @PostMapping("/eventos")
  @ResponseStatus(HttpStatus.CREATED)
  public Evento crear(@Valid @RequestBody Api.NuevoEvento body) {
    return service.crearEvento(body);
  }

  @GetMapping("/eventos/{id}")
  public Evento evento(@PathVariable UUID id) {
    return service.evento(id);
  }

  @PostMapping("/reservas")
  @ResponseStatus(HttpStatus.CREATED)
  public Reserva reservar(@Valid @RequestBody Api.NuevaReserva body) {
    return service.reservar(body);
  }

  @GetMapping("/reservas/{id}")
  public Reserva reserva(@PathVariable UUID id) {
    return service.reserva(id);
  }

  @PatchMapping("/reservas/{id}/estado")
  public Reserva estado(@PathVariable UUID id, @Valid @RequestBody Api.CambioEstado body) {
    return service.estado(id, body);
  }

  @GetMapping("/eventos/{id}/estadisticas")
  public Api.Estadisticas estadisticas(@PathVariable UUID id) {
    return service.estadisticas(id);
  }
}

package cl.duoc.reservas;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class Api {
  private Api() {}

  public record NuevoEvento(
      @NotBlank @Size(max = 100) String nombre,
      @NotNull @Future Instant fecha,
      @Min(1) @Max(1000000) int capacidad,
      @NotNull @DecimalMin("0.00") @Digits(integer = 12, fraction = 2) BigDecimal precio) {}

  public record NuevaReserva(
      @NotNull UUID eventoId,
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,60}") String usuarioId,
      @NotNull Reserva.Estado estado) {}

  public record CambioEstado(@NotNull Reserva.Estado estado) {}

  public record Estadisticas(
      UUID eventoId,
      long reservasActivas,
      long pendientes,
      long vendidas,
      long canceladas,
      BigDecimal ingresos) {}
}

package cl.duoc.tickets;

import jakarta.validation.constraints.*;
import java.util.*;

public final class Api {
  private Api() {}

  public record NuevoTicket(
      @NotNull UUID reservaId,
      @NotBlank @Size(max = 100) String titular,
      @NotBlank @Size(max = 30) String asiento) {}

  public record EditarTicket(
      @NotBlank @Size(max = 100) String titular, @NotBlank @Size(max = 30) String asiento) {}

  public record Historial(
      List<Ticket> content, int page, int size, long totalElements, int totalPages) {}
}

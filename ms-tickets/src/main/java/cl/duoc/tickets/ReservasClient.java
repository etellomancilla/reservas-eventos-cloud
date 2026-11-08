package cl.duoc.tickets;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ReservasClient {
  public record Reserva(
      UUID id, UUID eventoId, String usuarioId, String estado, BigDecimal precioReserva) {}

  public record Evento(UUID id, String nombre, Instant fecha) {}

  private final RestClient client;

  public ReservasClient(@Value("${reservas.url}") String url) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(3));
    factory.setReadTimeout(Duration.ofSeconds(5));
    client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  public Reserva reserva(UUID id) {
    return read("/api/reservas/" + id, Reserva.class);
  }

  public Evento evento(UUID id) {
    return read("/api/eventos/" + id, Evento.class);
  }

  private <T> T read(String uri, Class<T> type) {
    try {
      T value = client.get().uri(uri).retrieve().body(type);
      if (value == null)
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Respuesta vacía de Reservas");
      return value;
    } catch (HttpClientErrorException.NotFound ex) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Reserva o evento inexistente");
    } catch (RestClientException ex) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "No se pudo consultar ms-reservas");
    }
  }
}

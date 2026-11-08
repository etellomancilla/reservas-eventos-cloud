package cl.duoc.reservas;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:reservastest;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
@AutoConfigureMockMvc
class ReservasIntegrationTest {
  @Autowired ReservasService service;
  @Autowired ReservaRepository reservas;
  @Autowired EventoRepository eventos;
  @Autowired MockMvc mvc;

  @BeforeEach
  void clean() {
    reservas.deleteAll();
    eventos.deleteAll();
  }

  Evento event(int capacity) {
    return service.crearEvento(
        new Api.NuevoEvento(
            "Conferencia", Instant.now().plusSeconds(86400), capacity, new BigDecimal("15000")));
  }

  @Test
  void reportsSalesReservationsAndCancellation() {
    var e = event(10);
    var a = service.reservar(new Api.NuevaReserva(e.id, "usuarioX", Reserva.Estado.PAGADA));
    service.reservar(new Api.NuevaReserva(e.id, "usuarioY", Reserva.Estado.PAGADA));
    service.reservar(new Api.NuevaReserva(e.id, "usuarioZ", Reserva.Estado.RESERVADA));
    var s = service.estadisticas(e.id);
    assertThat(s.reservasActivas()).isEqualTo(3);
    assertThat(s.vendidas()).isEqualTo(2);
    assertThat(s.pendientes()).isEqualTo(1);
    assertThat(s.ingresos()).isEqualByComparingTo("30000");
    service.estado(a.id, new Api.CambioEstado(Reserva.Estado.CANCELADA));
    s = service.estadisticas(e.id);
    assertThat(s.canceladas()).isEqualTo(1);
    assertThat(s.ingresos()).isEqualByComparingTo("15000");
  }

  @Test
  void emptyEventHasZeroStatistics() {
    assertThat(service.estadisticas(event(2).id).ingresos()).isEqualByComparingTo("0");
  }

  @Test
  void paymentUsesReservedPriceAndIsIdempotent() {
    var e = event(2);
    var r = service.reservar(new Api.NuevaReserva(e.id, "usuarioX", Reserva.Estado.RESERVADA));
    e.precio = new BigDecimal("20000");
    eventos.saveAndFlush(e);
    service.estado(r.id, new Api.CambioEstado(Reserva.Estado.PAGADA));
    service.estado(r.id, new Api.CambioEstado(Reserva.Estado.PAGADA));
    assertThat(service.estadisticas(e.id).ingresos()).isEqualByComparingTo("15000");
  }

  @Test
  void rejectsInvalidTransitions() {
    var e = event(1);
    var r = service.reservar(new Api.NuevaReserva(e.id, "usuarioX", Reserva.Estado.PAGADA));
    assertThatThrownBy(() -> service.estado(r.id, new Api.CambioEstado(Reserva.Estado.RESERVADA)))
        .hasMessageContaining("409");
    service.estado(r.id, new Api.CambioEstado(Reserva.Estado.CANCELADA));
    assertThatThrownBy(() -> service.estado(r.id, new Api.CambioEstado(Reserva.Estado.PAGADA)))
        .hasMessageContaining("409");
  }

  @Test
  void capacityCannotBeOversoldConcurrently() throws Exception {
    var e = event(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<Boolean> task =
          () -> {
            start.await();
            try {
              service.reservar(new Api.NuevaReserva(e.id, "usuarioX", Reserva.Estado.PAGADA));
              return true;
            } catch (org.springframework.web.server.ResponseStatusException ex) {
              assertThat(ex.getStatusCode().value()).isEqualTo(409);
              return false;
            }
          };
      var a = executor.submit(task);
      var b = executor.submit(task);
      start.countDown();
      assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(service.estadisticas(e.id).vendidas()).isEqualTo(1);
  }

  @Test
  void concurrentPaymentCannotResurrectCancelledReservation() throws Exception {
    var e = event(1);
    var r = service.reservar(new Api.NuevaReserva(e.id, "usuarioX", Reserva.Estado.RESERVADA));
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var pay =
          executor.submit(
              () -> {
                start.await();
                try {
                  service.estado(r.id, new Api.CambioEstado(Reserva.Estado.PAGADA));
                } catch (org.springframework.web.server.ResponseStatusException ex) {
                  assertThat(ex.getStatusCode().value()).isEqualTo(409);
                }
                return true;
              });
      var cancel =
          executor.submit(
              () -> {
                start.await();
                service.estado(r.id, new Api.CambioEstado(Reserva.Estado.CANCELADA));
                return true;
              });
      start.countDown();
      pay.get(10, TimeUnit.SECONDS);
      cancel.get(10, TimeUnit.SECONDS);
    }
    assertThat(service.reserva(r.id).estado).isEqualTo(Reserva.Estado.CANCELADA);
  }

  @Test
  void restValidationAndMissingResources() throws Exception {
    mvc.perform(post("/api/eventos").contentType("application/json").content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/eventos/" + UUID.randomUUID())).andExpect(status().isNotFound());
    mvc.perform(get("/api/reservas/not-a-uuid")).andExpect(status().isBadRequest());
    var e = event(1);
    mvc.perform(
            post("/api/reservas")
                .contentType("application/json")
                .content(
                    "{\"eventoId\":\""
                        + e.id
                        + "\",\"usuarioId\":\"../escape\",\"estado\":\"PAGADA\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createsAndReadsThroughRest() throws Exception {
    var e = event(1);
    mvc.perform(
            post("/api/reservas")
                .contentType("application/json")
                .content(
                    "{\"eventoId\":\""
                        + e.id
                        + "\",\"usuarioId\":\"usuarioX\",\"estado\":\"RESERVADA\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.estado").value("RESERVADA"));
    mvc.perform(get("/api/eventos/" + e.id + "/estadisticas"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pendientes").value(1));
  }
}

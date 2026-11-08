package cl.duoc.tickets;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:ticketstest;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "tickets.cleanup-delay-ms=86400000"
    })
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TicketsIntegrationTest {
  static final Path ROOT = temp();

  static Path temp() {
    try {
      return Files.createTempDirectory("tickets-efs-test-");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("efs.path", () -> ROOT.toString());
  }

  @Autowired TicketsService service;
  @Autowired TicketRepository repo;
  @Autowired PdfService pdf;
  @Autowired MockMvc mvc;
  @MockitoBean ObjectStore store;
  @MockitoBean ReservasClient reservas;
  final Map<String, byte[]> objects = new ConcurrentHashMap<>();
  UUID eventId;

  @BeforeEach
  void setup() {
    repo.deleteAll();
    objects.clear();
    eventId = UUID.randomUUID();
    when(reservas.evento(any()))
        .thenAnswer(
            i ->
                new ReservasClient.Evento(
                    i.getArgument(0), "Conferencia Cloud", Instant.now().plusSeconds(86400)));
    when(reservas.reserva(any()))
        .thenAnswer(
            i ->
                new ReservasClient.Reserva(
                    i.getArgument(0), eventId, "usuarioX", "PAGADA", new BigDecimal("15000")));
    doAnswer(
            i -> {
              objects.put(i.getArgument(0), Files.readAllBytes((Path) i.getArgument(1)));
              return null;
            })
        .when(store)
        .put(anyString(), any());
    when(store.get(anyString()))
        .thenAnswer(
            i -> {
              byte[] data = objects.get(i.getArgument(0));
              if (data == null) throw new IllegalStateException("Missing");
              return data;
            });
    doAnswer(
            i -> {
              objects.remove(i.getArgument(0));
              return null;
            })
        .when(store)
        .delete(anyString());
  }

  Ticket create() {
    return service.create(new Api.NuevoTicket(UUID.randomUUID(), "Ana Pérez", "A-10"));
  }

  String pdfText(byte[] bytes) throws IOException {
    try (var doc = Loader.loadPDF(bytes)) {
      return new PDFTextStripper().getText(doc);
    }
  }

  @Test
  void createsActualPersonalizedPdfOnEfsAndAutomaticallyUploads() throws Exception {
    var t = create();
    byte[] local = Files.readAllBytes(pdf.path(t.s3Key));
    assertThat(t.estadoArchivo).isEqualTo(Ticket.Estado.SINCRONIZADO);
    assertThat(t.s3Key).isEqualTo("evento" + eventId + "/usuarioX/ticket" + t.id + ".pdf");
    assertThat(objects.get(t.s3Key)).isEqualTo(local);
    assertThat(t.sha256).isEqualTo(TicketsService.hash(local));
    assertThat(pdfText(local)).contains("Ana Pérez", "A-10", "Conferencia Cloud", t.id.toString());
  }

  @Test
  void s3FailurePersistsPendingRecordAndAllowsRetry() {
    doThrow(new IllegalStateException("S3 down")).when(store).put(anyString(), any());
    assertThatThrownBy(this::create).isInstanceOf(StorageFailure.class);
    Ticket t = repo.findAll().getFirst();
    assertThat(t.estadoArchivo).isEqualTo(Ticket.Estado.PENDIENTE_S3);
    assertThat(Files.exists(pdf.path(t.s3Key))).isTrue();
    assertThatThrownBy(() -> service.download(t.id)).hasMessageContaining("409");
    doAnswer(
            i -> {
              objects.put(i.getArgument(0), Files.readAllBytes((Path) i.getArgument(1)));
              return null;
            })
        .when(store)
        .put(anyString(), any());
    service.upload(t.id);
    service.upload(t.id);
    assertThat(repo.count()).isEqualTo(1);
    assertThat(objects).hasSize(1);
    assertThat(service.get(t.id).estadoArchivo).isEqualTo(Ticket.Estado.SINCRONIZADO);
  }

  @Test
  void modificationReplacesSameS3KeyAndContent() throws Exception {
    var t = create();
    String oldHash = t.sha256;
    var updated = service.edit(t.id, new Api.EditarTicket("Ana González", "B-20"));
    assertThat(updated.s3Key).isEqualTo(t.s3Key);
    assertThat(updated.sha256).isNotEqualTo(oldHash);
    assertThat(objects).hasSize(1);
    assertThat(pdfText(service.download(t.id)))
        .contains("Ana González", "B-20")
        .doesNotContain("A-10");
  }

  @Test
  void failedUpdateDoesNotServeStalePdf() {
    var t = create();
    doThrow(new IllegalStateException("S3 down")).when(store).put(anyString(), any());
    assertThatThrownBy(() -> service.edit(t.id, new Api.EditarTicket("Nuevo titular", "Z-1")))
        .isInstanceOf(StorageFailure.class);
    assertThat(service.get(t.id).estadoArchivo).isEqualTo(Ticket.Estado.PENDIENTE_S3);
    assertThatThrownBy(() -> service.download(t.id)).hasMessageContaining("409");
  }

  @Test
  void deletionRemovesBothFilesAndRetainsHistory() {
    var t = create();
    service.delete(t.id);
    service.delete(t.id);
    assertThat(objects).isEmpty();
    assertThat(Files.exists(pdf.path(t.s3Key))).isFalse();
    assertThat(service.get(t.id).estadoArchivo).isEqualTo(Ticket.Estado.ELIMINADO);
    assertThatThrownBy(() -> service.download(t.id)).hasMessageContaining("404");
    assertThat(
            service
                .history(eventId, null, null, null, Ticket.Estado.ELIMINADO, 0, 20)
                .totalElements())
        .isEqualTo(1);
  }

  @Test
  void failedDeletionCanBeRetried() {
    var t = create();
    doThrow(new IllegalStateException("down")).when(store).delete(anyString());
    assertThatThrownBy(() -> service.delete(t.id)).isInstanceOf(StorageFailure.class);
    assertThat(service.get(t.id).estadoArchivo).isEqualTo(Ticket.Estado.BORRANDO);
    doAnswer(
            i -> {
              objects.remove(i.getArgument(0));
              return null;
            })
        .when(store)
        .delete(anyString());
    service.delete(t.id);
    assertThat(service.get(t.id).estadoArchivo).isEqualTo(Ticket.Estado.ELIMINADO);
  }

  @Test
  void downloadsReadS3AndVerifyIntegrity() throws Exception {
    var t = create();
    Files.delete(pdf.path(t.s3Key));
    assertThat(pdfText(service.download(t.id))).contains("Ana Pérez");
    objects.put(t.s3Key, "corrupt".getBytes());
    assertThatThrownBy(() -> service.download(t.id)).hasMessageContaining("502");
  }

  @Test
  void filtersAndPaginationAreCombined() {
    Instant from = Instant.now().minusSeconds(1);
    var a = create();
    create();
    var page =
        service.history(
            eventId,
            "usuarioX",
            from,
            Instant.now().plusSeconds(1),
            Ticket.Estado.SINCRONIZADO,
            0,
            1);
    assertThat(page.totalElements()).isEqualTo(2);
    assertThat(page.content()).hasSize(1);
    assertThat(page.totalPages()).isEqualTo(2);
    assertThat(service.history(eventId, "usuarioY", null, null, null, 0, 20).content()).isEmpty();
    assertThat(
            service.history(eventId, null, null, a.creadoEn.minusSeconds(1), null, 0, 20).content())
        .isEmpty();
    assertThatThrownBy(() -> service.history(null, null, from, from, null, 0, 20))
        .hasMessageContaining("400");
  }

  @Test
  void zipFiltersByUserAndEventAndPreservesPdfBytes() throws Exception {
    var a = create();
    var b = create();
    Set<String> names = new HashSet<>();
    try (var zip = new ZipInputStream(new ByteArrayInputStream(service.zip(eventId, "usuarioX")))) {
      for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
        names.add(entry.getName());
        assertThat(zip.readAllBytes()).isEqualTo(objects.get(entry.getName()));
      }
    }
    assertThat(names).containsExactlyInAnyOrder(a.s3Key, b.s3Key);
    assertThatThrownBy(() -> service.zip(eventId, "usuarioY")).hasMessageContaining("404");
    assertThatThrownBy(() -> service.zip(null, null)).hasMessageContaining("400");
  }

  @Test
  void retentionOnlyCleansConfirmedOldTemporaries() {
    var a = create();
    a.actualizadoEn = Instant.now().minus(Duration.ofHours(25));
    repo.saveAndFlush(a);
    var b = create();
    b.actualizadoEn = a.actualizadoEn;
    b.estadoArchivo = Ticket.Estado.PENDIENTE_S3;
    repo.saveAndFlush(b);
    service.cleanup();
    assertThat(Files.exists(pdf.path(a.s3Key))).isFalse();
    assertThat(Files.exists(pdf.path(b.s3Key))).isTrue();
    assertThat(objects).hasSize(2);
    assertThat(service.upload(a.id).estadoArchivo).isEqualTo(Ticket.Estado.SINCRONIZADO);
  }

  @Test
  void rejectsDuplicateCancelledAndPathTraversal() {
    var a = create();
    assertThatThrownBy(() -> service.create(new Api.NuevoTicket(a.reservaId, "Ana", "A")))
        .hasMessageContaining("409");
    when(reservas.reserva(any()))
        .thenReturn(
            new ReservasClient.Reserva(
                UUID.randomUUID(), eventId, "usuarioX", "CANCELADA", BigDecimal.ZERO));
    assertThatThrownBy(this::create).hasMessageContaining("409");
    assertThatThrownBy(() -> pdf.path("../../escape.pdf"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void restReturnsPdfHeadersAndValidationErrors() throws Exception {
    var t = create();
    mvc.perform(get("/api/tickets/" + t.id + "/archivo"))
        .andExpect(status().isOk())
        .andExpect(content().contentType("application/pdf"))
        .andExpect(header().string("X-Checksum-SHA256", t.sha256));
    mvc.perform(post("/api/tickets").contentType("application/json").content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/tickets?size=101")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/tickets/" + UUID.randomUUID() + "/archivo"))
        .andExpect(status().isNotFound());
  }

  @Test
  void restReportsTicketIdOnStorageFailure() throws Exception {
    doThrow(new IllegalStateException("down")).when(store).put(anyString(), any());
    mvc.perform(
            post("/api/tickets")
                .contentType("application/json")
                .content(
                    "{\"reservaId\":\""
                        + UUID.randomUUID()
                        + "\",\"titular\":\"Ana\",\"asiento\":\"A-1\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.ticketId").isNotEmpty());
  }
}

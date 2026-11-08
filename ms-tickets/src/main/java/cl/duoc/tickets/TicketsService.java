package cl.duoc.tickets;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TicketsService {
  private final TicketRepository repo;
  private final ReservasClient reservas;
  private final ObjectStore store;
  private final PdfService pdf;
  private final long retentionHours;

  public TicketsService(
      TicketRepository repo,
      ReservasClient reservas,
      ObjectStore store,
      PdfService pdf,
      @Value("${tickets.retention-hours}") long retentionHours) {
    this.repo = repo;
    this.reservas = reservas;
    this.store = store;
    this.pdf = pdf;
    this.retentionHours = retentionHours;
  }

  // Una réplica de Tickets en esta evaluación: serializa edición/subida/borrado/limpieza.
  // Las llamadas saveAndFlush confirman cada estado antes de contactar S3.
  public synchronized Ticket create(Api.NuevoTicket body) {
    if (repo.existsByReservaId(body.reservaId()))
      throw error(409, "La reserva ya tiene un ticket; usa su ID para reintentar");
    var r = reservas.reserva(body.reservaId());
    if ("CANCELADA".equals(r.estado())) throw error(409, "La reserva está cancelada");
    if (r.usuarioId() == null || !r.usuarioId().matches("[A-Za-z0-9_-]{1,60}"))
      throw error(502, "Usuario inválido en Reservas");
    var e = reservas.evento(r.eventoId());
    Ticket t = new Ticket();
    t.id = UUID.randomUUID();
    t.reservaId = r.id();
    t.eventoId = r.eventoId();
    t.usuarioId = r.usuarioId();
    t.titular = body.titular();
    t.asiento = body.asiento();
    t.eventoNombre = e.nombre();
    t.eventoFecha = e.fecha();
    t.estadoReserva = r.estado();
    t.precio = r.precioReserva();
    t.creadoEn = Instant.now();
    t.actualizadoEn = t.creadoEn;
    t.s3Key = "evento" + t.eventoId + "/" + t.usuarioId + "/ticket" + t.id + ".pdf";
    t.estadoArchivo = Ticket.Estado.PENDIENTE_S3;
    repo.saveAndFlush(t);
    return renderAndUpload(t);
  }

  public Ticket get(UUID id) {
    return repo.findById(id).orElseThrow(() -> error(404, "Ticket inexistente"));
  }

  private void active(Ticket t) {
    if (t.estadoArchivo == Ticket.Estado.ELIMINADO) throw error(404, "Ticket eliminado");
    if (t.estadoArchivo == Ticket.Estado.BORRANDO)
      throw error(409, "Borrado pendiente; reintenta DELETE");
  }

  public synchronized Ticket edit(UUID id, Api.EditarTicket body) {
    Ticket t = get(id);
    active(t);
    t.titular = body.titular();
    t.asiento = body.asiento();
    t.actualizadoEn = Instant.now();
    t.estadoArchivo = Ticket.Estado.PENDIENTE_S3;
    // Invalidar el temporal anterior antes de una posible falla de generación.
    t.temporalDisponible = false;
    t.sha256 = null;
    repo.saveAndFlush(t);
    return renderAndUpload(t);
  }

  private Ticket renderAndUpload(Ticket t) {
    try {
      Path file = pdf.generate(t);
      t.sha256 = hash(Files.readAllBytes(file));
      t.temporalDisponible = true;
      repo.saveAndFlush(t);
      return uploadFile(t, file);
    } catch (StorageFailure e) {
      throw e;
    } catch (IOException e) {
      throw new StorageFailure(t.id, "No se pudo generar PDF en EFS; reintenta la subida", e);
    }
  }

  private Ticket uploadFile(Ticket t, Path file) {
    try {
      store.put(t.s3Key, file);
    } catch (RuntimeException e) {
      throw new StorageFailure(
          t.id,
          "S3 no disponible; PDF conservado en EFS, reintenta POST /api/tickets/" + t.id + "/s3",
          e);
    }
    t.estadoArchivo = Ticket.Estado.SINCRONIZADO;
    return repo.saveAndFlush(t);
  }

  public synchronized Ticket upload(UUID id) {
    Ticket t = get(id);
    active(t);
    Path file = pdf.path(t.s3Key);
    if (t.estadoArchivo == Ticket.Estado.SINCRONIZADO
        && (!t.temporalDisponible || !Files.exists(file))) {
      download(id); // Confirma que el PDF definitivo conserva la integridad.
      return t;
    }
    if (!t.temporalDisponible || !Files.exists(file)) return renderAndUpload(t);
    try {
      if (!Objects.equals(t.sha256, hash(Files.readAllBytes(file)))) return renderAndUpload(t);
    } catch (IOException ex) {
      throw new StorageFailure(t.id, "No se puede leer el PDF temporal", ex);
    }
    return uploadFile(t, file);
  }

  public synchronized byte[] download(UUID id) {
    Ticket t = get(id);
    active(t);
    if (t.estadoArchivo != Ticket.Estado.SINCRONIZADO)
      throw error(409, "PDF pendiente de sincronizar");
    byte[] data;
    try {
      data = store.get(t.s3Key);
    } catch (RuntimeException ex) {
      throw new StorageFailure(t.id, "No se pudo descargar el PDF desde S3", ex);
    }
    if (!hash(data).equals(t.sha256)) throw error(502, "El PDF de S3 no coincide con su SHA-256");
    return data;
  }

  public synchronized void delete(UUID id) {
    Ticket t = get(id);
    if (t.estadoArchivo == Ticket.Estado.ELIMINADO) return;
    t.estadoArchivo = Ticket.Estado.BORRANDO;
    repo.saveAndFlush(t);
    try {
      store.delete(t.s3Key);
      Files.deleteIfExists(pdf.path(t.s3Key));
    } catch (Exception e) {
      throw new StorageFailure(t.id, "Borrado incompleto; reintenta DELETE con el mismo ID", e);
    }
    t.estadoArchivo = Ticket.Estado.ELIMINADO;
    t.temporalDisponible = false;
    t.eliminadoEn = Instant.now();
    t.actualizadoEn = t.eliminadoEn;
    repo.saveAndFlush(t);
  }

  private Specification<Ticket> filters(
      UUID evento, String usuario, Instant desde, Instant hasta, Ticket.Estado estado) {
    return (root, query, cb) -> {
      var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
      if (evento != null) predicates.add(cb.equal(root.get("eventoId"), evento));
      if (usuario != null) predicates.add(cb.equal(root.get("usuarioId"), usuario));
      if (desde != null) predicates.add(cb.greaterThanOrEqualTo(root.get("creadoEn"), desde));
      if (hasta != null) predicates.add(cb.lessThan(root.get("creadoEn"), hasta));
      if (estado != null) predicates.add(cb.equal(root.get("estadoArchivo"), estado));
      return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
    };
  }

  public Api.Historial history(
      UUID evento,
      String usuario,
      Instant desde,
      Instant hasta,
      Ticket.Estado estado,
      int page,
      int size) {
    if (page < 0 || size < 1 || size > 100)
      throw error(400, "page debe ser >=0 y size entre 1 y 100");
    if (desde != null && hasta != null && !desde.isBefore(hasta))
      throw error(400, "desde debe ser anterior a hasta");
    var result =
        repo.findAll(
            filters(evento, usuario, desde, hasta, estado),
            PageRequest.of(page, size, Sort.by("creadoEn", "id").descending()));
    return new Api.Historial(
        result.getContent(), page, size, result.getTotalElements(), result.getTotalPages());
  }

  public synchronized byte[] zip(UUID evento, String usuario) {
    if (evento == null && (usuario == null || usuario.isBlank()))
      throw error(400, "Indica eventoId o usuarioId");
    var result =
        repo.findAll(
            filters(evento, usuario, null, null, Ticket.Estado.SINCRONIZADO),
            PageRequest.of(0, 200, Sort.by("id")));
    if (result.getTotalElements() > 200)
      throw error(413, "Descarga limitada a 200 tickets; combina evento y usuario");
    if (result.isEmpty()) throw error(404, "No hay archivos para esos filtros");
    try (var buffer = new ByteArrayOutputStream();
        var zip = new ZipOutputStream(buffer)) {
      for (Ticket t : result) {
        zip.putNextEntry(new ZipEntry(t.s3Key));
        zip.write(download(t.id));
        zip.closeEntry();
      }
      zip.finish();
      return buffer.toByteArray();
    } catch (IOException e) {
      throw error(500, "No se pudo construir ZIP");
    }
  }

  @Scheduled(fixedDelayString = "${tickets.cleanup-delay-ms}")
  public synchronized void cleanup() {
    var old =
        repo.findByEstadoArchivoAndTemporalDisponibleTrueAndActualizadoEnBefore(
            Ticket.Estado.SINCRONIZADO,
            Instant.now().minus(Duration.ofHours(retentionHours)),
            PageRequest.of(0, 100));
    for (Ticket t : old) {
      try {
        Files.deleteIfExists(pdf.path(t.s3Key));
        t.temporalDisponible = false;
        repo.saveAndFlush(t);
      } catch (IOException e) {
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("No se pudo limpiar temporal {}", t.id);
      }
    }
  }

  static String hash(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static ResponseStatusException error(int status, String message) {
    return new ResponseStatusException(HttpStatus.valueOf(status), message);
  }
}

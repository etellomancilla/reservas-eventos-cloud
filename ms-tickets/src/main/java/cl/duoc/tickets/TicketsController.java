package cl.duoc.tickets;

import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tickets")
public class TicketsController {
  private final TicketsService service;

  public TicketsController(TicketsService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Ticket create(@Valid @RequestBody Api.NuevoTicket body) {
    return service.create(body);
  }

  @GetMapping("/{id}")
  public Ticket get(@PathVariable UUID id) {
    return service.get(id);
  }

  @PostMapping("/{id}/s3")
  public Ticket upload(@PathVariable UUID id) {
    return service.upload(id);
  }

  @PutMapping("/{id}")
  public Ticket edit(@PathVariable UUID id, @Valid @RequestBody Api.EditarTicket body) {
    return service.edit(id, body);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) {
    service.delete(id);
  }

  @GetMapping("/{id}/archivo")
  public ResponseEntity<byte[]> download(@PathVariable UUID id) {
    byte[] data = service.download(id);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ticket" + id + ".pdf\"")
        .header("X-Checksum-SHA256", TicketsService.hash(data))
        .body(data);
  }

  @GetMapping("/descarga")
  public ResponseEntity<byte[]> zip(
      @RequestParam(required = false) UUID eventoId,
      @RequestParam(required = false) String usuarioId) {
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/zip"))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"tickets.zip\"")
        .body(service.zip(eventoId, usuarioId));
  }

  @GetMapping
  public Api.Historial history(
      @RequestParam(required = false) UUID eventoId,
      @RequestParam(required = false) String usuarioId,
      @RequestParam(required = false) Instant desde,
      @RequestParam(required = false) Instant hasta,
      @RequestParam(required = false) Ticket.Estado estadoArchivo,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return service.history(eventoId, usuarioId, desde, hasta, estadoArchivo, page, size);
  }
}

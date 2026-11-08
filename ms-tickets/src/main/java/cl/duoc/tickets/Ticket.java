package cl.duoc.tickets;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    indexes = {
      @Index(name = "idx_ticket_evento_fecha", columnList = "eventoId,creadoEn"),
      @Index(name = "idx_ticket_usuario_fecha", columnList = "usuarioId,creadoEn")
    })
public class Ticket {
  public enum Estado {
    PENDIENTE_S3,
    SINCRONIZADO,
    BORRANDO,
    ELIMINADO
  }

  @Id public UUID id;

  @Column(nullable = false, unique = true)
  public UUID reservaId;

  @Column(nullable = false)
  public UUID eventoId;

  @Column(nullable = false, length = 60)
  public String usuarioId;

  @Column(nullable = false, length = 100)
  public String titular;

  @Column(nullable = false, length = 30)
  public String asiento;

  @Column(nullable = false)
  public String s3Key;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public Estado estadoArchivo;

  public String sha256;
  public Instant creadoEn;
  public Instant actualizadoEn;
  public Instant eliminadoEn;
  public boolean temporalDisponible;
  public String eventoNombre;
  public Instant eventoFecha;
  public String estadoReserva;

  @Column(precision = 14, scale = 2)
  public BigDecimal precio;

  public Ticket() {}
}

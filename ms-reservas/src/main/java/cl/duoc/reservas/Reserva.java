package cl.duoc.reservas;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(indexes = @Index(name = "idx_reserva_evento_estado", columnList = "eventoId,estado"))
public class Reserva {
  public enum Estado {
    RESERVADA,
    PAGADA,
    CANCELADA
  }

  @Id public UUID id;

  @Column(nullable = false)
  public UUID eventoId;

  @Column(nullable = false, length = 60)
  public String usuarioId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  public Estado estado;

  @Column(nullable = false, precision = 14, scale = 2)
  public BigDecimal precioPagado;

  @Column(nullable = false, precision = 14, scale = 2)
  public BigDecimal precioReserva;

  @Column(nullable = false)
  public Instant creadaEn;

  protected Reserva() {}

  Reserva(UUID eventoId, String usuarioId, Estado estado, BigDecimal precio) {
    this.id = UUID.randomUUID();
    this.eventoId = eventoId;
    this.usuarioId = usuarioId;
    this.estado = estado;
    this.precioReserva = precio;
    this.precioPagado = estado == Estado.PAGADA ? precio : BigDecimal.ZERO;
    this.creadaEn = Instant.now();
  }
}

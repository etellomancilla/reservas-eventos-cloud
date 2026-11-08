package cl.duoc.reservas;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
public class Evento {
  @Id public UUID id;

  @Column(nullable = false, length = 100)
  public String nombre;

  @Column(nullable = false)
  public Instant fecha;

  public int capacidad;

  @Column(nullable = false, precision = 14, scale = 2)
  public BigDecimal precio;

  protected Evento() {}

  Evento(String nombre, Instant fecha, int capacidad, BigDecimal precio) {
    this.id = UUID.randomUUID();
    this.nombre = nombre;
    this.fecha = fecha;
    this.capacidad = capacidad;
    this.precio = precio;
  }
}

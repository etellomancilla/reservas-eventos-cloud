package cl.duoc.reservas;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ReservaRepository extends JpaRepository<Reserva, UUID> {
  long countByEventoIdAndEstado(UUID eventoId, Reserva.Estado estado);

  long countByEventoIdAndEstadoNot(UUID eventoId, Reserva.Estado estado);

  @Query(
      "select coalesce(sum(r.precioPagado),0) from Reserva r where r.eventoId=:id and"
          + " r.estado=cl.duoc.reservas.Reserva.Estado.PAGADA")
  BigDecimal ingresos(@Param("id") UUID id);
}

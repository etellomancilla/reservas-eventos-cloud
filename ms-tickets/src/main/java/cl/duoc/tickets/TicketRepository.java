package cl.duoc.tickets;

import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

public interface TicketRepository
    extends JpaRepository<Ticket, UUID>, JpaSpecificationExecutor<Ticket> {
  boolean existsByReservaId(UUID reservaId);

  List<Ticket> findByEstadoArchivoAndTemporalDisponibleTrueAndActualizadoEnBefore(
      Ticket.Estado estado, Instant fecha, Pageable pageable);
}

package cl.duoc.reservas;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface EventoRepository extends JpaRepository<Evento, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select e from Evento e where e.id=:id")
  Optional<Evento> lockById(@Param("id") UUID id);
}

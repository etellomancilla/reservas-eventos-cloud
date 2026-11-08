package cl.duoc.tickets;

import java.util.UUID;

public class StorageFailure extends RuntimeException {
  public final UUID ticketId;

  public StorageFailure(UUID id, String message, Throwable cause) {
    super(message, cause);
    ticketId = id;
  }
}

package cl.duoc.tickets;

import java.nio.file.Path;

public interface ObjectStore {
  void put(String key, Path path);

  byte[] get(String key);

  void delete(String key);
}

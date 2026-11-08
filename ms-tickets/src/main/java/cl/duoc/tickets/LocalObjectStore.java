package cl.duoc.tickets;

import java.io.*;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Simulación explícita para probar sin cuenta AWS; no constituye evidencia de S3. */
@Component
@Profile("local")
public class LocalObjectStore implements ObjectStore {
  private final Path root;

  public LocalObjectStore(@Value("${tickets.local-store}") String path) {
    root = Path.of(path).toAbsolutePath().normalize();
  }

  private Path path(String key) {
    Path file = root.resolve(key).normalize();
    if (!file.startsWith(root)) throw new IllegalArgumentException("Ruta inválida");
    return file;
  }

  public void put(String key, Path source) {
    try {
      Path target = path(key);
      Files.createDirectories(target.getParent());
      Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public byte[] get(String key) {
    try {
      return Files.readAllBytes(path(key));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public void delete(String key) {
    try {
      Files.deleteIfExists(path(key));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}

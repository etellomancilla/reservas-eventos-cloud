package cl.duoc.tickets;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PdfService {
  private final Path root;

  public PdfService(@Value("${efs.path}") String root) {
    this.root = Path.of(root).toAbsolutePath().normalize();
  }

  public Path path(String key) {
    Path file = root.resolve(key).normalize();
    if (!file.startsWith(root) || file.equals(root))
      throw new IllegalArgumentException("Ruta fuera de EFS");
    return file;
  }

  public Path generate(Ticket t) throws IOException {
    Path target = path(t.s3Key);
    Files.createDirectories(target.getParent());
    Path temp = Files.createTempFile(target.getParent(), "ticket-", ".tmp");
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage();
      doc.addPage(page);
      PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      try (PDPageContentStream out = new PDPageContentStream(doc, page)) {
        out.beginText();
        out.setFont(font, 12);
        out.setLeading(20);
        out.newLineAtOffset(45, 745);
        for (String line :
            List.of(
                "TICKET - RESERVA DE EVENTO",
                "Evento: " + t.eventoNombre,
                "Fecha UTC: " + t.eventoFecha,
                "Ticket: " + t.id,
                "Reserva: " + t.reservaId,
                "Usuario: " + t.usuarioId,
                "Titular: " + t.titular,
                "Asiento: " + t.asiento,
                "Estado al emitir: " + t.estadoReserva,
                "Precio CLP: " + t.precio,
                "Actualizado UTC: " + t.actualizadoEn)) {
          String safe = printable(line, font);
          for (int start = 0; start < safe.length(); start += 72) {
            out.showText(safe.substring(start, Math.min(safe.length(), start + 72)));
            out.newLine();
          }
        }
        out.endText();
      }
      doc.save(temp.toFile());
      try {
        Files.move(
            temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ex) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
      return target;
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private String printable(String value, PDFont font) {
    StringBuilder out = new StringBuilder();
    value
        .codePoints()
        .forEach(
            cp -> {
              String character = new String(Character.toChars(cp));
              try {
                font.encode(character);
                out.append(character);
              } catch (Exception e) {
                out.append('?');
              }
            });
    return out.toString();
  }
}

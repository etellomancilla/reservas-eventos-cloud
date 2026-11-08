package cl.duoc.tickets;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TicketsApplication {
  public static void main(String[] args) {
    SpringApplication.run(TicketsApplication.class, args);
  }
}

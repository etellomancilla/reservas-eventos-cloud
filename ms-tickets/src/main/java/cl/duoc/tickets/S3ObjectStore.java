package cl.duoc.tickets;

import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Component
@Profile("!local & !test")
public class S3ObjectStore implements ObjectStore {
  private final S3Client client;
  private final String bucket;

  public S3ObjectStore(
      @Value("${tickets.bucket}") String bucket, @Value("${aws.region}") String region) {
    if (bucket.isBlank()) throw new IllegalArgumentException("Falta TICKETS_BUCKET");
    this.bucket = bucket;
    client =
        S3Client.builder()
            .region(Region.of(region))
            .overrideConfiguration(
                c ->
                    c.apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10)))
            .build();
  }

  public void put(String key, Path path) {
    client.putObject(
        r -> r.bucket(bucket).key(key).contentType("application/pdf"), RequestBody.fromFile(path));
  }

  public byte[] get(String key) {
    return client.getObjectAsBytes(r -> r.bucket(bucket).key(key)).asByteArray();
  }

  public void delete(String key) {
    client.deleteObject(r -> r.bucket(bucket).key(key));
  }

  @PreDestroy
  public void close() {
    client.close();
  }
}

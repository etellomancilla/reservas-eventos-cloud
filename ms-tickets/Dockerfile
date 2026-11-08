FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
# Las pruebas deben usar dobles de S3/HTTP y una BD de prueba, sin AWS real.
RUN mvn -B clean verify

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl fonts-liberation \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app/efs && chown -R 10001:10001 /app
WORKDIR /app
COPY --from=build /build/target/*.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

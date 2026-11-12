# syntax=docker/dockerfile:1

# ---- Build stage ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw

COPY src/ src/
# Tests run in CI (they need Docker for Testcontainers), so skip them here.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -DskipTests package \
 && cp target/*.jar application.jar \
 && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /application

RUN groupadd --system spring \
 && useradd --system --gid spring --no-create-home --shell /usr/sbin/nologin spring \
 && mkdir -p /app/data/files \
 && chown -R spring:spring /app/data

# Uploaded proof files live here (a named volume in prod). A new empty volume gets
# this folder and its owner from the image, so the non-root user can write to it.
VOLUME /app/data

# Copy layers from least to most frequently changed for better caching.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER spring:spring

ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "application.jar"]

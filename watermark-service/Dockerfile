# syntax=docker/dockerfile:1
FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests -Dspotless.check.skip=true package \
    && cp target/watermark-service-*.jar /build/app.jar

FROM eclipse-temurin:17.0.20.1_1-jre-noble
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 vms \
    && useradd --system --uid 10001 --gid vms --home /app vms \
    && mkdir -p /app/assets /data/storage /tmp/vms-watermark \
    && chown -R vms:vms /app /data /tmp/vms-watermark
WORKDIR /app
COPY --chown=vms:vms assets/logo.png /app/assets/logo.png
COPY --from=build --chown=vms:vms /build/app.jar app.jar
USER vms
EXPOSE 8081
# Half the container memory for the JVM; ffmpeg runs as a separate process and needs the rest.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:+ExitOnOutOfMemoryError" \
    WATERMARK_LOGO_PATH=/app/assets/logo.png \
    WATERMARK_WORK_DIR=/tmp/vms-watermark
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD curl -fsS http://localhost:8081/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

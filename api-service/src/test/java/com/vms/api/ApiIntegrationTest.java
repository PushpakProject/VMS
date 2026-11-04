package com.vms.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vms.api.outbox.OutboxRepository;
import com.vms.api.video.domain.VideoStatus;
import com.vms.api.video.infrastructure.ProcessingAttemptRepository;
import com.vms.api.video.infrastructure.VideoRepository;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the real application against real PostgreSQL and Kafka (Testcontainers): Flyway migrations +
 * Hibernate schema validation, the full HTTP upload flow, the outbox publishing to Kafka and the
 * idempotent result consumer. Requires Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ApiIntegrationTest {

  private static final String PASSWORD = "integration-test-password";

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");

  @Container @ServiceConnection
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.2");

  static Path storageDir;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) throws Exception {
    storageDir = Files.createTempDirectory("vms-it-storage");
    registry.add("vms.auth.jwt-secret", () -> "c2VjcmV0LXNlY3JldC1zZWNyZXQtc2VjcmV0LXNlY3JldA==");
    registry.add("vms.auth.cookie-secure", () -> "false");
    registry.add("vms.auth.cors-allowed-origins", () -> "http://localhost:8088");
    registry.add("vms.storage.provider", () -> "local");
    registry.add("vms.storage.local-dir", () -> storageDir.toString());
    registry.add("vms.storage.local-signing-key", () -> "integration-test-signing-key-0123456789");
    registry.add("vms.demo.user-email", () -> "it-operator@vms.test");
    registry.add("vms.demo.user-password", () -> PASSWORD);
    registry.add("vms.demo.admin-email", () -> "it-admin@vms.test");
    registry.add("vms.demo.admin-password", () -> PASSWORD);
    registry.add("vms.outbox.poll-interval", () -> "200");
  }

  @Autowired MockMvc mvc;
  @Autowired JsonMapper json;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired VideoRepository videos;
  @Autowired ProcessingAttemptRepository attempts;
  @Autowired OutboxRepository outbox;

  @Test
  void uploadIsVerifiedPublishedAndCompletedThroughKafka() throws Exception {
    String token = login("it-operator@vms.test");

    // 1. Register the recording and get a signed upload URL.
    byte[] clip = new byte[4096];
    JsonNode initiated =
        call(
            post("/api/v1/videos")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"trackingId\":\"IT-001\",\"contentType\":\"video/webm;codecs=vp9,opus\","
                        + "\"sizeBytes\":"
                        + clip.length
                        + "}"),
            201);
    UUID videoId = UUID.fromString(initiated.get("videoId").asString());
    String uploadUrl = initiated.get("upload").get("url").asString();

    // 2. Complete before uploading is rejected; nothing is published.
    call(completeRequest(token, videoId), 409);

    // 3. Upload through the resumable session (local storage emulator), then complete.
    assertThat(initiated.get("upload").get("protocol").asString()).isEqualTo("RESUMABLE");
    var status =
        mvc.perform(
                put(URI.create(uploadUrl))
                    .header("Origin", "http://localhost:8088")
                    .header("Content-Range", "bytes */" + clip.length))
            .andReturn()
            .getResponse();
    assertThat(status.getStatus()).isEqualTo(308);
    assertThat(status.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:8088");
    var uploadResponse =
        mvc.perform(
                put(URI.create(uploadUrl))
                    .header("Origin", "http://localhost:8088")
                    .header("Content-Range", "bytes 0-" + (clip.length - 1) + "/" + clip.length)
                    .content(clip))
            .andReturn()
            .getResponse();
    assertThat(uploadResponse.getStatus()).as(uploadResponse.getContentAsString()).isEqualTo(200);
    JsonNode completed = call(completeRequest(token, videoId), 202);
    assertThat(completed.get("status").asString()).isEqualTo("UPLOADED");

    // 4. The outbox relay publishes video.uploaded.v1 with the server-generated keys.
    ConsumerRecord<String, String> event = awaitUploadedEvent(videoId);
    JsonNode payload = json.readTree(event.value()).get("payload");
    assertThat(payload.get("run").asInt()).isEqualTo(1);
    assertThat(payload.get("rawObjectKey").asString()).startsWith("raw/").endsWith(".webm");
    assertThat(payload.get("finalObjectKey").asString()).startsWith("videos/").endsWith(".mp4");
    await().atMost(Duration.ofSeconds(10)).until(() -> outbox.countPending() == 0);

    // 5. Watermark results flow back: STARTED, then COMPLETED (delivered twice).
    sendResult(videoId, "STARTED", null);
    await()
        .atMost(Duration.ofSeconds(15))
        .until(() -> videos.findById(videoId).orElseThrow().getStatus() == VideoStatus.PROCESSING);
    String completedEvent = resultJson(UUID.randomUUID(), videoId, "COMPLETED", payload);
    kafka.send("video.watermark.result.v1", videoId.toString(), completedEvent).get();
    kafka.send("video.watermark.result.v1", videoId.toString(), completedEvent).get();
    await()
        .atMost(Duration.ofSeconds(15))
        .until(() -> videos.findById(videoId).orElseThrow().getStatus() == VideoStatus.READY);
    Thread.sleep(500);
    assertThat(attempts.findByVideoIdOrderByRunNumberAscAttemptNumberAsc(videoId))
        .singleElement()
        .satisfies(a -> assertThat(a.getStatus().name()).isEqualTo("COMPLETED"));

    // 6. Playback is now allowed; another user cannot even see the video.
    call(
        get("/api/v1/videos/" + videoId + "/playback-url")
            .header("Authorization", "Bearer " + token),
        200);
    String other = signUpAndLogin();
    call(get("/api/v1/videos/" + videoId).header("Authorization", "Bearer " + other), 404);

    // 7. Business metrics are exported for Prometheus.
    String metrics =
        mvc.perform(get("/actuator/prometheus"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(metrics)
        .contains("vms_uploads_completed_total")
        .contains("vms_videos_ready_total")
        .contains("vms_outbox_pending");
  }

  @Test
  void idempotencyKeyReturnsTheSameVideo() throws Exception {
    String token = login("it-operator@vms.test");
    String key = UUID.randomUUID().toString();
    String body = "{\"trackingId\":\"IT-002\",\"contentType\":\"video/webm\",\"sizeBytes\":10}";
    JsonNode first =
        call(
            post("/api/v1/videos")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
            201);
    JsonNode second =
        call(
            post("/api/v1/videos")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body),
            201);
    assertThat(second.get("videoId").asString()).isEqualTo(first.get("videoId").asString());
  }

  private String login(String email) throws Exception {
    return call(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"login\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"),
            200)
        .get("accessToken")
        .asString();
  }

  private String signUpAndLogin() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    return call(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"other-"
                        + suffix
                        + "@vms.test\",\"username\":\"other-"
                        + suffix
                        + "\",\"displayName\":\"Other\",\"password\":\""
                        + PASSWORD
                        + "\"}"),
            201)
        .get("accessToken")
        .asString();
  }

  private org.springframework.test.web.servlet.RequestBuilder completeRequest(
      String token, UUID videoId) {
    return post("/api/v1/videos/" + videoId + "/complete")
        .header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"durationMs\":60000}");
  }

  private JsonNode call(org.springframework.test.web.servlet.RequestBuilder request, int status)
      throws Exception {
    String body =
        mvc.perform(request)
            .andExpect(status().is(status))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
  }

  private ConsumerRecord<String, String> awaitUploadedEvent(UUID videoId) {
    Map<String, Object> config =
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            KAFKA.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG,
            "it-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class);
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
      consumer.subscribe(List.of("video.uploaded.v1"));
      long deadline = System.currentTimeMillis() + 20_000;
      while (System.currentTimeMillis() < deadline) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
          if (videoId.toString().equals(record.key())) {
            return record;
          }
        }
      }
    }
    throw new AssertionError("video.uploaded.v1 was not published for " + videoId);
  }

  private void sendResult(UUID videoId, String outcome, JsonNode uploaded) throws Exception {
    kafka
        .send(
            "video.watermark.result.v1",
            videoId.toString(),
            resultJson(UUID.randomUUID(), videoId, outcome, uploaded))
        .get();
  }

  private static String resultJson(UUID eventId, UUID videoId, String outcome, JsonNode uploaded) {
    String finalKey =
        uploaded == null ? "null" : "\"" + uploaded.get("finalObjectKey").asString() + "\"";
    return "{\"eventId\":\""
        + eventId
        + "\",\"eventType\":\"WATERMARK_RESULT\",\"version\":1,"
        + "\"occurredAt\":\"2026-11-03T10:00:00Z\",\"correlationId\":null,"
        + "\"payload\":{\"videoId\":\""
        + videoId
        + "\",\"run\":1,\"attempt\":1,\"outcome\":\""
        + outcome
        + "\",\"finalObjectKey\":"
        + finalKey
        + ",\"finalSizeBytes\":900,\"durationMs\":60000,\"errorCode\":null,"
        + "\"errorMessage\":null}}";
  }
}

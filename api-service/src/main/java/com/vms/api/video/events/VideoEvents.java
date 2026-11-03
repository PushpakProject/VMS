package com.vms.api.video.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Versioned Kafka event contracts (JSON Schemas in docs/contracts). Each service owns its own copy
 * of these records; there is no shared library coupling the deployables.
 */
public final class VideoEvents {

  public static final String VIDEO_UPLOADED = "VIDEO_UPLOADED";
  public static final String WATERMARK_RESULT = "WATERMARK_RESULT";
  public static final int VERSION = 1;

  private VideoEvents() {}

  public record Envelope<T>(
      UUID eventId,
      String eventType,
      int version,
      Instant occurredAt,
      String correlationId,
      T payload) {}

  public record VideoUploaded(
      UUID videoId,
      UUID ownerId,
      String trackingId,
      int run,
      String rawObjectKey,
      String finalObjectKey,
      String contentType,
      long sizeBytes) {}

  public enum Outcome {
    STARTED,
    RETRYING,
    COMPLETED,
    FAILED
  }

  public record WatermarkResult(
      UUID videoId,
      int run,
      int attempt,
      Outcome outcome,
      String finalObjectKey,
      Long finalSizeBytes,
      Integer durationMs,
      String errorCode,
      String errorMessage) {}
}

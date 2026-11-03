package com.vms.watermark.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * This service's copy of the event contracts (see docs/contracts). Unknown fields are ignored so
 * producers can add fields without breaking consumers.
 */
public final class WatermarkEvents {

  public static final String WATERMARK_RESULT = "WATERMARK_RESULT";
  public static final int VERSION = 1;

  private WatermarkEvents() {}

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

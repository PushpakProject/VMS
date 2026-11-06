package com.vms.api.video.api;

import com.vms.api.video.domain.FailureCode;
import com.vms.api.video.domain.ProcessingAttempt;
import com.vms.api.video.domain.TrackingId;
import com.vms.api.video.domain.Video;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** API contracts for videos. Entities are never serialized directly. */
public final class VideoDtos {

  private VideoDtos() {}

  public record InitiateUploadRequest(
      @NotBlank @Pattern(regexp = TrackingId.REGEX) String trackingId,
      @NotBlank @Size(max = 100) String contentType,
      @NotNull @Positive Long sizeBytes,
      Instant recordedAt) {}

  /**
   * How the browser uploads. SIMPLE: one PUT to {@code url} with {@code headers}. RESUMABLE: PUT
   * pieces of {@code chunkSizeBytes} to the session {@code url} with {@code Content-Range}.
   */
  public record UploadInstruction(
      String protocol,
      String url,
      String method,
      Map<String, String> headers,
      Instant expiresAt,
      Integer chunkSizeBytes) {}

  public record InitiateUploadResponse(UUID videoId, String status, UploadInstruction upload) {}

  public record CompleteUploadRequest(@Min(0) @Max(3_600_000) Integer durationMs) {}

  public record VideoResponse(
      UUID id,
      String trackingId,
      String status,
      String contentType,
      Long sizeBytes,
      Integer durationMs,
      String failureCode,
      String failureMessage,
      boolean retryable,
      Instant recordedAt,
      Instant uploadedAt,
      Instant processedAt,
      Instant createdAt,
      List<AttemptResponse> attempts) {

    static VideoResponse from(Video video, List<ProcessingAttempt> attempts) {
      FailureCode failure = video.getFailureCode();
      return new VideoResponse(
          video.getId(),
          video.getTrackingId(),
          video.getStatus().name(),
          video.getContentType(),
          video.getFinalSizeBytes() != null ? video.getFinalSizeBytes() : video.getSizeBytes(),
          video.getDurationMs(),
          failure == null ? null : failure.name(),
          failure == null ? null : failure.message(),
          failure != null && failure.retryable(),
          video.getRecordedAt(),
          video.getUploadedAt(),
          video.getProcessedAt(),
          video.getCreatedAt(),
          attempts.stream().map(AttemptResponse::from).toList());
    }
  }

  public record AttemptResponse(
      int run,
      int attempt,
      String status,
      String errorCode,
      String errorMessage,
      Instant startedAt,
      Instant finishedAt) {

    static AttemptResponse from(ProcessingAttempt a) {
      return new AttemptResponse(
          a.getRunNumber(),
          a.getAttemptNumber(),
          a.getStatus().name(),
          a.getErrorCode(),
          a.getErrorMessage(),
          a.getStartedAt(),
          a.getFinishedAt());
    }
  }

  public record PlaybackUrlResponse(String url, Instant expiresAt) {}
}

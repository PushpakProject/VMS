package com.vms.api.video.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Video aggregate. Status only changes through the domain methods below, each of which enforces the
 * {@link VideoStatus} state machine. Optimistic locking ({@code @Version}) protects against
 * concurrent updates from the API and the result listener.
 */
@Entity
@Table(name = "videos")
public class Video {

  @Id private UUID id;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "tracking_id", nullable = false)
  private String trackingId;

  @Column(name = "raw_object_key", nullable = false)
  private String rawObjectKey;

  @Column(name = "object_key")
  private String objectKey;

  @Column(name = "content_type", nullable = false)
  private String contentType;

  @Column(name = "size_bytes")
  private Long sizeBytes;

  @Column(name = "final_size_bytes")
  private Long finalSizeBytes;

  @Column(name = "duration_ms")
  private Integer durationMs;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private VideoStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "failure_code")
  private FailureCode failureCode;

  @Column(name = "processing_run", nullable = false)
  private int processingRun;

  @Column(name = "client_upload_id", nullable = false)
  private UUID clientUploadId;

  @Column(name = "recorded_at")
  private Instant recordedAt;

  @Column(name = "uploaded_at")
  private Instant uploadedAt;

  @Column(name = "processed_at")
  private Instant processedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version private long version;

  protected Video() {}

  public static Video create(
      UUID ownerId,
      TrackingId trackingId,
      String baseContentType,
      long declaredSizeBytes,
      UUID clientUploadId,
      Instant recordedAt,
      Instant now) {
    Video video = new Video();
    video.id = UUID.randomUUID();
    video.ownerId = ownerId;
    video.trackingId = trackingId.value();
    video.contentType = baseContentType;
    video.sizeBytes = declaredSizeBytes;
    video.rawObjectKey = ObjectKeys.raw(ownerId, video.id, baseContentType);
    video.status = VideoStatus.UPLOADING;
    video.clientUploadId = clientUploadId;
    video.recordedAt = recordedAt;
    video.createdAt = now;
    video.updatedAt = now;
    return video;
  }

  /** Upload verified in storage; starts a new processing run. */
  public void markUploaded(long actualSizeBytes, Integer durationMs, Instant now) {
    transitionTo(VideoStatus.UPLOADED, now);
    this.sizeBytes = actualSizeBytes;
    this.durationMs = durationMs;
    this.uploadedAt = now;
    this.processingRun++;
  }

  public void markProcessing(Instant now) {
    transitionTo(VideoStatus.PROCESSING, now);
  }

  public void markReady(String finalKey, long finalSize, Integer finalDurationMs, Instant now) {
    transitionTo(VideoStatus.READY, now);
    this.objectKey = finalKey;
    this.finalSizeBytes = finalSize;
    if (finalDurationMs != null) {
      this.durationMs = finalDurationMs;
    }
    this.processedAt = now;
    this.failureCode = null;
  }

  public void markFailed(FailureCode code, Instant now) {
    transitionTo(VideoStatus.FAILED, now);
    this.failureCode = code;
    this.processedAt = now;
  }

  /** FAILED back to UPLOADED for another processing run (owner retry / admin reprocess). */
  public void requeue(Instant now) {
    transitionTo(VideoStatus.UPLOADED, now);
    this.failureCode = null;
    this.processingRun++;
  }

  /** No object ever arrived for this registration. */
  public void markAbandoned(Instant now) {
    transitionTo(VideoStatus.ABANDONED, now);
  }

  public void softDelete(Instant now) {
    this.deletedAt = now;
    this.updatedAt = now;
  }

  public boolean isOwnedBy(UUID userId) {
    return ownerId.equals(userId);
  }

  public boolean isDeleted() {
    return deletedAt != null;
  }

  public String watermarkedObjectKey() {
    return ObjectKeys.watermarked(ownerId, id);
  }

  private void transitionTo(VideoStatus next, Instant now) {
    if (!status.canTransitionTo(next)) {
      throw new IllegalStateTransitionException(status, next);
    }
    this.status = next;
    this.updatedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public String getTrackingId() {
    return trackingId;
  }

  public String getRawObjectKey() {
    return rawObjectKey;
  }

  public String getObjectKey() {
    return objectKey;
  }

  public String getContentType() {
    return contentType;
  }

  public Long getSizeBytes() {
    return sizeBytes;
  }

  public Long getFinalSizeBytes() {
    return finalSizeBytes;
  }

  public Integer getDurationMs() {
    return durationMs;
  }

  public VideoStatus getStatus() {
    return status;
  }

  public FailureCode getFailureCode() {
    return failureCode;
  }

  public int getProcessingRun() {
    return processingRun;
  }

  public UUID getClientUploadId() {
    return clientUploadId;
  }

  public Instant getRecordedAt() {
    return recordedAt;
  }

  public Instant getUploadedAt() {
    return uploadedAt;
  }

  public Instant getProcessedAt() {
    return processedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}

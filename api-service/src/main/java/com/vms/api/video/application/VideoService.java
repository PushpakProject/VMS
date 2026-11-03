package com.vms.api.video.application;

import static com.vms.api.video.infrastructure.VideoSpecifications.hasStatus;
import static com.vms.api.video.infrastructure.VideoSpecifications.notDeleted;
import static com.vms.api.video.infrastructure.VideoSpecifications.ownedBy;
import static com.vms.api.video.infrastructure.VideoSpecifications.trackingIdContains;

import com.vms.api.common.config.KafkaTopicsProperties;
import com.vms.api.common.config.RecordingProperties;
import com.vms.api.common.error.ApiException;
import com.vms.api.common.error.ErrorCode;
import com.vms.api.common.security.CurrentUser;
import com.vms.api.common.web.RequestIdFilter;
import com.vms.api.outbox.OutboxService;
import com.vms.api.storage.StoragePort;
import com.vms.api.storage.StoragePort.ObjectStat;
import com.vms.api.storage.StoragePort.SignedUrl;
import com.vms.api.storage.StoragePort.UploadTarget;
import com.vms.api.video.domain.FailureCode;
import com.vms.api.video.domain.ProcessingAttempt;
import com.vms.api.video.domain.TrackingId;
import com.vms.api.video.domain.Video;
import com.vms.api.video.domain.VideoStatus;
import com.vms.api.video.events.VideoEvents;
import com.vms.api.video.infrastructure.ProcessingAttemptRepository;
import com.vms.api.video.infrastructure.VideoRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Video use cases. Controllers stay thin; storage and messaging are reached through ports. */
@Service
public class VideoService {

  private static final Logger log = LoggerFactory.getLogger(VideoService.class);
  private static final Set<VideoStatus> ALREADY_UPLOADED =
      EnumSet.of(VideoStatus.UPLOADED, VideoStatus.PROCESSING, VideoStatus.READY);

  private final VideoRepository videos;
  private final ProcessingAttemptRepository attempts;
  private final StoragePort storage;
  private final OutboxService outbox;
  private final RecordingProperties recording;
  private final KafkaTopicsProperties topics;
  private final Clock clock;

  public VideoService(
      VideoRepository videos,
      ProcessingAttemptRepository attempts,
      StoragePort storage,
      OutboxService outbox,
      RecordingProperties recording,
      KafkaTopicsProperties topics,
      Clock clock) {
    this.videos = videos;
    this.attempts = attempts;
    this.storage = storage;
    this.outbox = outbox;
    this.recording = recording;
    this.topics = topics;
    this.clock = clock;
  }

  /**
   * Registers a recording and returns a pre-signed upload. Idempotent on the client's upload id:
   * repeating the call returns the same video with a fresh URL (e.g. after the URL expired).
   */
  @Transactional
  public UploadSession initiateUpload(CurrentUser user, UUID clientUploadId, InitiateUpload cmd) {
    Video video =
        videos
            .findByOwnerIdAndClientUploadId(user.id(), clientUploadId)
            .orElseGet(() -> videos.save(newVideo(user, clientUploadId, cmd)));
    UploadTarget target =
        video.getStatus() == VideoStatus.UPLOADING
            ? storage.createUploadTarget(
                video.getRawObjectKey(),
                video.getContentType(),
                recording.maxUploadBytes(),
                recording.uploadUrlTtl())
            : null;
    return new UploadSession(video, target);
  }

  /**
   * Verifies the object really exists in storage (the client is never trusted), then marks the
   * video UPLOADED and records the event in the outbox in the same transaction.
   */
  @Transactional
  public Video completeUpload(CurrentUser user, UUID videoId, Integer durationMs) {
    Video video = loadAccessible(user, videoId);
    if (ALREADY_UPLOADED.contains(video.getStatus())) {
      return video;
    }
    if (video.getStatus() != VideoStatus.UPLOADING) {
      throw new ApiException(ErrorCode.INVALID_STATE);
    }
    ObjectStat stat =
        storage
            .stat(video.getRawObjectKey())
            .orElseThrow(() -> new ApiException(ErrorCode.UPLOAD_NOT_FOUND));
    Instant now = clock.instant();
    FailureCode rejection = validateUploadedObject(video, stat, durationMs);
    if (rejection != null) {
      deleteQuietly(video.getRawObjectKey());
      video.markFailed(rejection, now);
      log.info("Upload rejected for video {}: {}", video.getId(), rejection);
      return video;
    }
    video.markUploaded(stat.sizeBytes(), durationMs, now);
    publishUploaded(video, now);
    return video;
  }

  @Transactional(readOnly = true)
  public Page<Video> list(CurrentUser user, String trackingId, VideoStatus status, Pageable page) {
    Specification<Video> spec = notDeleted();
    if (!user.admin()) {
      spec = spec.and(ownedBy(user.id()));
    }
    if (trackingId != null && !trackingId.isBlank()) {
      spec = spec.and(trackingIdContains(trackingId.trim()));
    }
    if (status != null) {
      spec = spec.and(hasStatus(status));
    }
    return videos.findAll(spec, page);
  }

  @Transactional(readOnly = true)
  public VideoDetails get(CurrentUser user, UUID videoId) {
    Video video = loadAccessible(user, videoId);
    List<ProcessingAttempt> history =
        attempts.findByVideoIdOrderByRunNumberAscAttemptNumberAsc(video.getId());
    return new VideoDetails(video, history);
  }

  /** Short-lived signed GET, only for watermarked (READY) videos. */
  @Transactional(readOnly = true)
  public SignedUrl playbackUrl(CurrentUser user, UUID videoId) {
    Video video = loadAccessible(user, videoId);
    if (video.getStatus() != VideoStatus.READY || video.getObjectKey() == null) {
      throw new ApiException(ErrorCode.NOT_READY);
    }
    return storage.createDownloadUrl(video.getObjectKey(), recording.playbackUrlTtl());
  }

  /**
   * Owner retry (only retryable failures) or admin reprocess (any failure). Both require the
   * original recording to still exist.
   */
  @Transactional
  public Video retry(CurrentUser user, UUID videoId, boolean adminOverride) {
    Video video = loadAccessible(user, videoId);
    if (video.getStatus() != VideoStatus.FAILED) {
      throw new ApiException(ErrorCode.INVALID_STATE);
    }
    boolean retryable = video.getFailureCode() == null || video.getFailureCode().retryable();
    if (!retryable && !adminOverride) {
      throw new ApiException(ErrorCode.NOT_RETRYABLE);
    }
    if (storage.stat(video.getRawObjectKey()).isEmpty()) {
      throw new ApiException(ErrorCode.RAW_NOT_AVAILABLE);
    }
    Instant now = clock.instant();
    video.requeue(now);
    publishUploaded(video, now);
    return video;
  }

  @Transactional
  public void delete(CurrentUser user, UUID videoId) {
    Video video = loadAccessible(user, videoId);
    video.softDelete(clock.instant());
    deleteQuietly(video.getRawObjectKey());
    if (video.getObjectKey() != null) {
      deleteQuietly(video.getObjectKey());
    }
  }

  /** Owner-or-admin check. Other users' videos are reported as not found (no existence leak). */
  private Video loadAccessible(CurrentUser user, UUID videoId) {
    return videos
        .findById(videoId)
        .filter(v -> !v.isDeleted())
        .filter(v -> user.admin() || v.isOwnedBy(user.id()))
        .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
  }

  private Video newVideo(CurrentUser user, UUID clientUploadId, InitiateUpload cmd) {
    String baseType = RecordingProperties.baseContentType(cmd.contentType());
    if (!recording.isAllowedContentType(baseType)) {
      throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }
    if (cmd.sizeBytes() > recording.maxUploadBytes()) {
      throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
    }
    return Video.create(
        user.id(),
        TrackingId.of(cmd.trackingId()),
        baseType,
        cmd.sizeBytes(),
        clientUploadId,
        cmd.recordedAt(),
        clock.instant());
  }

  private FailureCode validateUploadedObject(Video video, ObjectStat stat, Integer durationMs) {
    if (stat.sizeBytes() > recording.maxUploadBytes()) {
      return FailureCode.TOO_LARGE;
    }
    if (!video.getContentType().equals(RecordingProperties.baseContentType(stat.contentType()))) {
      return FailureCode.UNSUPPORTED_FORMAT;
    }
    if (durationMs != null && durationMs > recording.maxAcceptedDurationMs()) {
      return FailureCode.TOO_LONG;
    }
    return null;
  }

  private void publishUploaded(Video video, Instant now) {
    UUID eventId = UUID.randomUUID();
    var payload =
        new VideoEvents.VideoUploaded(
            video.getId(),
            video.getOwnerId(),
            video.getTrackingId(),
            video.getProcessingRun(),
            video.getRawObjectKey(),
            video.watermarkedObjectKey(),
            video.getContentType(),
            video.getSizeBytes());
    var envelope =
        new VideoEvents.Envelope<>(
            eventId,
            VideoEvents.VIDEO_UPLOADED,
            VideoEvents.VERSION,
            now,
            MDC.get(RequestIdFilter.MDC_KEY),
            payload);
    outbox.append(
        eventId, video.getId(), VideoEvents.VIDEO_UPLOADED, topics.videoUploadedTopic(), envelope);
  }

  private void deleteQuietly(String key) {
    try {
      storage.delete(key);
    } catch (RuntimeException ex) {
      log.warn(
          "Could not delete object (lifecycle rule will clean up): {}",
          ex.getClass().getSimpleName());
    }
  }

  public record InitiateUpload(
      String trackingId, String contentType, long sizeBytes, Instant recordedAt) {}

  public record UploadSession(Video video, UploadTarget upload) {}

  public record VideoDetails(Video video, List<ProcessingAttempt> attempts) {}
}

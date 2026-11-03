package com.vms.api.video.application;

import com.vms.api.outbox.ProcessedEvent;
import com.vms.api.outbox.ProcessedEventRepository;
import com.vms.api.storage.StoragePort;
import com.vms.api.video.domain.FailureCode;
import com.vms.api.video.domain.ProcessingAttempt;
import com.vms.api.video.domain.Video;
import com.vms.api.video.domain.VideoStatus;
import com.vms.api.video.events.VideoEvents.Envelope;
import com.vms.api.video.events.VideoEvents.WatermarkResult;
import com.vms.api.video.infrastructure.ProcessingAttemptRepository;
import com.vms.api.video.infrastructure.VideoRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies watermark results to the video and its attempt history. Idempotent: an event id is
 * processed once, results from an older processing run are ignored, and transitions the state
 * machine doesn't allow (e.g. a duplicate COMPLETED) are skipped.
 */
@Service
public class WatermarkResultHandler {

  static final String CONSUMER = "api-service";
  private static final Logger log = LoggerFactory.getLogger(WatermarkResultHandler.class);

  private final VideoRepository videos;
  private final ProcessingAttemptRepository attempts;
  private final ProcessedEventRepository processedEvents;
  private final StoragePort storage;
  private final Clock clock;

  public WatermarkResultHandler(
      VideoRepository videos,
      ProcessingAttemptRepository attempts,
      ProcessedEventRepository processedEvents,
      StoragePort storage,
      Clock clock) {
    this.videos = videos;
    this.attempts = attempts;
    this.processedEvents = processedEvents;
    this.storage = storage;
    this.clock = clock;
  }

  @Transactional
  public void handle(Envelope<WatermarkResult> event) {
    if (processedEvents.existsById(event.eventId())) {
      log.debug("Duplicate result event {} ignored", event.eventId());
      return;
    }
    processedEvents.save(new ProcessedEvent(event.eventId(), CONSUMER, clock.instant()));
    WatermarkResult result = event.payload();
    Video video = videos.findById(result.videoId()).orElse(null);
    if (video == null) {
      log.warn("Result for unknown video {} ignored", result.videoId());
      return;
    }
    if (result.run() < video.getProcessingRun()) {
      log.info("Stale result (run {}) for video {} ignored", result.run(), video.getId());
      return;
    }
    Instant now = clock.instant();
    recordAttempt(video, result, now);
    applyToVideo(video, result, now);
  }

  private void recordAttempt(Video video, WatermarkResult result, Instant now) {
    ProcessingAttempt attempt =
        attempts
            .findByVideoIdAndRunNumberAndAttemptNumber(
                video.getId(), result.run(), result.attempt())
            .orElseGet(
                () -> new ProcessingAttempt(video.getId(), result.run(), result.attempt(), now));
    ProcessingAttempt.Status status =
        switch (result.outcome()) {
          case STARTED -> ProcessingAttempt.Status.STARTED;
          case RETRYING -> ProcessingAttempt.Status.RETRYING;
          case COMPLETED -> ProcessingAttempt.Status.COMPLETED;
          case FAILED -> ProcessingAttempt.Status.FAILED;
        };
    attempt.record(status, result.errorCode(), safeMessage(result), now);
    attempts.save(attempt);
  }

  private void applyToVideo(Video video, WatermarkResult result, Instant now) {
    VideoStatus current = video.getStatus();
    switch (result.outcome()) {
      case STARTED -> {
        if (current.canTransitionTo(VideoStatus.PROCESSING)) {
          video.markProcessing(now);
        }
      }
      case RETRYING -> {
        // Status stays PROCESSING; the attempt row carries the error.
      }
      case COMPLETED -> {
        if (video.isDeleted()) {
          storage.delete(result.finalObjectKey());
        } else if (current.canTransitionTo(VideoStatus.READY)) {
          long size = result.finalSizeBytes() == null ? 0 : result.finalSizeBytes();
          video.markReady(result.finalObjectKey(), size, result.durationMs(), now);
        }
      }
      case FAILED -> {
        if (current.canTransitionTo(VideoStatus.FAILED) && current != VideoStatus.UPLOADING) {
          video.markFailed(FailureCode.fromWire(result.errorCode()), now);
        }
      }
    }
  }

  /** Stored messages come from our fixed code table, never raw exception text. */
  private static String safeMessage(WatermarkResult result) {
    return result.errorCode() == null ? null : FailureCode.fromWire(result.errorCode()).message();
  }
}

package com.vms.api.video.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vms.api.common.observability.VmsMetrics;
import com.vms.api.outbox.ProcessedEventRepository;
import com.vms.api.storage.StoragePort;
import com.vms.api.video.domain.FailureCode;
import com.vms.api.video.domain.ProcessingAttempt;
import com.vms.api.video.domain.TrackingId;
import com.vms.api.video.domain.Video;
import com.vms.api.video.domain.VideoStatus;
import com.vms.api.video.events.VideoEvents;
import com.vms.api.video.events.VideoEvents.Envelope;
import com.vms.api.video.events.VideoEvents.Outcome;
import com.vms.api.video.events.VideoEvents.WatermarkResult;
import com.vms.api.video.infrastructure.ProcessingAttemptRepository;
import com.vms.api.video.infrastructure.VideoRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WatermarkResultHandlerTest {

  private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

  private final VideoRepository videos = mock(VideoRepository.class);
  private final ProcessingAttemptRepository attempts = mock(ProcessingAttemptRepository.class);
  private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
  private final StoragePort storage = mock(StoragePort.class);
  private WatermarkResultHandler handler;
  private Video video;

  @BeforeEach
  void setUp() {
    handler =
        new WatermarkResultHandler(
            videos,
            attempts,
            processed,
            storage,
            new VmsMetrics(new SimpleMeterRegistry()),
            Clock.fixed(NOW, ZoneOffset.UTC));
    video =
        Video.create(
            UUID.randomUUID(),
            TrackingId.of("TRK-1"),
            "video/webm",
            1000,
            UUID.randomUUID(),
            NOW,
            NOW);
    video.markUploaded(1000, 60_000, NOW);
    when(videos.findById(video.getId())).thenReturn(Optional.of(video));
    when(attempts.findByVideoIdAndRunNumberAndAttemptNumber(any(), anyInt(), anyInt()))
        .thenReturn(Optional.empty());
  }

  private Envelope<WatermarkResult> event(Outcome outcome, int run, String errorCode) {
    return new Envelope<>(
        UUID.randomUUID(),
        VideoEvents.WATERMARK_RESULT,
        1,
        NOW,
        null,
        new WatermarkResult(
            video.getId(),
            run,
            1,
            outcome,
            outcome == Outcome.COMPLETED ? video.watermarkedObjectKey() : null,
            outcome == Outcome.COMPLETED ? 900L : null,
            outcome == Outcome.COMPLETED ? 60_050 : null,
            errorCode,
            "internal detail that must not be stored"));
  }

  @Test
  void startedThenCompletedMakesVideoReady() {
    handler.handle(event(Outcome.STARTED, 1, null));
    assertThat(video.getStatus()).isEqualTo(VideoStatus.PROCESSING);

    handler.handle(event(Outcome.COMPLETED, 1, null));
    assertThat(video.getStatus()).isEqualTo(VideoStatus.READY);
    assertThat(video.getObjectKey()).isEqualTo(video.watermarkedObjectKey());
  }

  @Test
  void duplicateEventIdIsIgnored() {
    var event = event(Outcome.COMPLETED, 1, null);
    when(processed.existsById(event.eventId())).thenReturn(true);

    handler.handle(event);

    assertThat(video.getStatus()).isEqualTo(VideoStatus.UPLOADED);
    verify(attempts, never()).save(any());
  }

  @Test
  void duplicateCompletedAfterReadyIsHarmless() {
    handler.handle(event(Outcome.COMPLETED, 1, null));
    handler.handle(event(Outcome.COMPLETED, 1, null));
    assertThat(video.getStatus()).isEqualTo(VideoStatus.READY);
  }

  @Test
  void staleRunIsIgnored() {
    video.markFailed(FailureCode.PROCESSING_FAILED, NOW);
    video.requeue(NOW);

    handler.handle(event(Outcome.COMPLETED, 1, null));

    assertThat(video.getStatus()).isEqualTo(VideoStatus.UPLOADED);
  }

  @Test
  void failedStoresSafeMessageOnly() {
    handler.handle(event(Outcome.FAILED, 1, "CORRUPT_INPUT"));

    assertThat(video.getStatus()).isEqualTo(VideoStatus.FAILED);
    assertThat(video.getFailureCode()).isEqualTo(FailureCode.CORRUPT_INPUT);
    ArgumentCaptor<ProcessingAttempt> saved = ArgumentCaptor.forClass(ProcessingAttempt.class);
    verify(attempts).save(saved.capture());
    assertThat(saved.getValue().getErrorMessage()).isEqualTo(FailureCode.CORRUPT_INPUT.message());
  }

  @Test
  void completedForDeletedVideoRemovesOrphanedOutput() {
    video.softDelete(NOW);

    handler.handle(event(Outcome.COMPLETED, 1, null));

    verify(storage).delete(video.watermarkedObjectKey());
  }
}

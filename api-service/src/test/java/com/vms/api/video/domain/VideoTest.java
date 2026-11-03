package com.vms.api.video.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vms.api.common.error.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VideoTest {

  private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");
  private final UUID owner = UUID.randomUUID();

  private Video newVideo() {
    return Video.create(
        owner, TrackingId.of("TRK-001"), "video/webm", 1000, UUID.randomUUID(), NOW, NOW);
  }

  @Test
  void createGeneratesServerSideKeys() {
    Video video = newVideo();
    assertThat(video.getStatus()).isEqualTo(VideoStatus.UPLOADING);
    assertThat(video.getRawObjectKey()).isEqualTo("raw/" + owner + "/" + video.getId() + ".webm");
    assertThat(video.watermarkedObjectKey())
        .isEqualTo("videos/" + owner + "/" + video.getId() + ".mp4");
  }

  @Test
  void happyPathIncrementsRunAndStoresFinalKey() {
    Video video = newVideo();
    video.markUploaded(2000, 60_000, NOW);
    assertThat(video.getProcessingRun()).isEqualTo(1);
    video.markProcessing(NOW);
    video.markReady(video.watermarkedObjectKey(), 1900, 60_100, NOW);
    assertThat(video.getStatus()).isEqualTo(VideoStatus.READY);
    assertThat(video.getObjectKey()).isEqualTo(video.watermarkedObjectKey());
    assertThat(video.getDurationMs()).isEqualTo(60_100);
  }

  @Test
  void requeueStartsANewRun() {
    Video video = newVideo();
    video.markUploaded(2000, null, NOW);
    video.markFailed(FailureCode.PROCESSING_FAILED, NOW);
    video.requeue(NOW);
    assertThat(video.getStatus()).isEqualTo(VideoStatus.UPLOADED);
    assertThat(video.getProcessingRun()).isEqualTo(2);
    assertThat(video.getFailureCode()).isNull();
  }

  @Test
  void illegalTransitionThrows() {
    Video video = newVideo();
    assertThatThrownBy(() -> video.markReady("k", 1, null, NOW))
        .isInstanceOf(IllegalStateTransitionException.class);
  }

  @Test
  void trackingIdIsValidated() {
    assertThatThrownBy(() -> TrackingId.of("a b")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> TrackingId.of("ab")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> TrackingId.of("../etc")).isInstanceOf(ApiException.class);
    assertThat(TrackingId.of(" TRK_123-x ").value()).isEqualTo("TRK_123-x");
  }
}

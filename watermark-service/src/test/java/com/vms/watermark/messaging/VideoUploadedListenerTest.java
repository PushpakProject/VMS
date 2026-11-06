package com.vms.watermark.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vms.watermark.application.JobResult;
import com.vms.watermark.application.WatermarkJob;
import com.vms.watermark.config.WatermarkProperties;
import com.vms.watermark.exception.FailureCode;
import com.vms.watermark.exception.NonRetryableProcessingException;
import com.vms.watermark.exception.RetryableProcessingException;
import com.vms.watermark.messaging.WatermarkEvents.Outcome;
import com.vms.watermark.messaging.WatermarkEvents.WatermarkResult;
import com.vms.watermark.observability.WatermarkMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.retrytopic.RetryTopicHeaders;
import tools.jackson.databind.json.JsonMapper;

class VideoUploadedListenerTest {

  private final WatermarkJob job = mock(WatermarkJob.class);
  private final WatermarkResultPublisher publisher = mock(WatermarkResultPublisher.class);
  private VideoUploadedListener listener;
  private final UUID videoId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    WatermarkProperties properties =
        new WatermarkProperties(
            "ffmpeg",
            "ffprobe",
            "logo.png",
            "/tmp",
            Duration.ofMinutes(3),
            4,
            120,
            10,
            100_000_000,
            0.15,
            0.85,
            "veryfast",
            23,
            2);
    listener =
        new VideoUploadedListener(
            job,
            publisher,
            properties,
            JsonMapper.builder().build(),
            new WatermarkMetrics(new SimpleMeterRegistry()));
  }

  private ConsumerRecord<String, String> record(Integer attempt) {
    String json =
        """
        {"eventId":"%s","eventType":"VIDEO_UPLOADED","version":1,
         "occurredAt":"2026-11-03T10:00:00Z","correlationId":"req-12345678",
         "payload":{"videoId":"%s","ownerId":"%s","trackingId":"TRK-1","run":1,
                    "rawObjectKey":"raw/a.webm","finalObjectKey":"videos/a.mp4",
                    "contentType":"video/webm","sizeBytes":10}}
        """
            .formatted(UUID.randomUUID(), videoId, UUID.randomUUID());
    ConsumerRecord<String, String> record =
        new ConsumerRecord<>("video.uploaded.v1", 0, 0, videoId.toString(), json);
    if (attempt != null) {
      record
          .headers()
          .add(
              RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS, BigInteger.valueOf(attempt).toByteArray());
    }
    return record;
  }

  private List<WatermarkResult> published(int count) {
    ArgumentCaptor<WatermarkResult> captor = ArgumentCaptor.forClass(WatermarkResult.class);
    verify(publisher, times(count)).publish(captor.capture(), any());
    return captor.getAllValues();
  }

  @Test
  void publishesStartedThenCompleted() {
    when(job.execute(any())).thenReturn(new JobResult("videos/a.mp4", 900, 60_000, false));

    listener.onVideoUploaded(record(null));

    List<WatermarkResult> events = published(2);
    assertThat(events.get(0).outcome()).isEqualTo(Outcome.STARTED);
    assertThat(events.get(0).attempt()).isEqualTo(1);
    assertThat(events.get(1).outcome()).isEqualTo(Outcome.COMPLETED);
    assertThat(events.get(1).finalSizeBytes()).isEqualTo(900);
  }

  @Test
  void transientFailureReportsRetryingAndRethrows() {
    when(job.execute(any())).thenThrow(new RetryableProcessingException("GCS 503"));

    assertThatThrownBy(() -> listener.onVideoUploaded(record(2)))
        .isInstanceOf(RetryableProcessingException.class);

    WatermarkResult last = published(2).get(1);
    assertThat(last.outcome()).isEqualTo(Outcome.RETRYING);
    assertThat(last.attempt()).isEqualTo(2);
  }

  @Test
  void lastAttemptReportsFailed() {
    when(job.execute(any())).thenThrow(new RetryableProcessingException("GCS 503"));

    assertThatThrownBy(() -> listener.onVideoUploaded(record(4)))
        .isInstanceOf(RetryableProcessingException.class);

    assertThat(published(2).get(1).outcome()).isEqualTo(Outcome.FAILED);
  }

  @Test
  void permanentFailureReportsFailedWithCode() {
    when(job.execute(any()))
        .thenThrow(new NonRetryableProcessingException(FailureCode.CORRUPT_INPUT, "bad"));

    assertThatThrownBy(() -> listener.onVideoUploaded(record(null)))
        .isInstanceOf(NonRetryableProcessingException.class);

    WatermarkResult last = published(2).get(1);
    assertThat(last.outcome()).isEqualTo(Outcome.FAILED);
    assertThat(last.errorCode()).isEqualTo("CORRUPT_INPUT");
  }

  @Test
  void unreadableEventIsPermanent() {
    ConsumerRecord<String, String> bad =
        new ConsumerRecord<>("video.uploaded.v1", 0, 0, "k", "{oops");
    assertThatThrownBy(() -> listener.onVideoUploaded(bad))
        .isInstanceOf(NonRetryableProcessingException.class);
  }

  @Test
  void attemptHeaderIsDecoded() {
    assertThat(VideoUploadedListener.attemptOf(record(null))).isEqualTo(1);
    assertThat(VideoUploadedListener.attemptOf(record(3))).isEqualTo(3);
  }
}

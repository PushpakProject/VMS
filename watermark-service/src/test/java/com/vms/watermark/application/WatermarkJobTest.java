package com.vms.watermark.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vms.watermark.config.WatermarkProperties;
import com.vms.watermark.exception.FailureCode;
import com.vms.watermark.exception.NonRetryableProcessingException;
import com.vms.watermark.exception.RetryableProcessingException;
import com.vms.watermark.processing.FfprobeClient;
import com.vms.watermark.processing.MediaInfo;
import com.vms.watermark.processing.VideoProcessor;
import com.vms.watermark.storage.ObjectStoragePort;
import com.vms.watermark.storage.ObjectStoragePort.StoredObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WatermarkJobTest {

  @TempDir Path tmp;

  private final ObjectStoragePort storage = mock(ObjectStoragePort.class);
  private final FfprobeClient ffprobe = mock(FfprobeClient.class);
  private final VideoProcessor processor = mock(VideoProcessor.class);
  private WatermarkJob job;
  private JobRequest request;

  @BeforeEach
  void setUp() {
    WatermarkProperties properties =
        new WatermarkProperties(
            "ffmpeg",
            "ffprobe",
            "logo.png",
            tmp.toString(),
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
    job = new WatermarkJob(storage, ffprobe, processor, properties);
    UUID videoId = UUID.randomUUID();
    request =
        new JobRequest(
            videoId, 1, "raw/u/" + videoId + ".webm", "videos/u/" + videoId + ".mp4", "video/webm");
  }

  private void givenRawExists(long size) {
    when(storage.stat(request.rawObjectKey()))
        .thenReturn(Optional.of(new StoredObject(size, "video/webm", Map.of())));
  }

  private void givenProcessingWritesOutput() {
    doAnswer(
            inv -> {
              Files.writeString(inv.getArgument(2), "mp4-bytes");
              return null;
            })
        .when(processor)
        .watermark(any(), any(), any(), any());
  }

  @Test
  void skipsWorkWhenOutputAlreadyExists() {
    when(storage.stat(request.finalObjectKey()))
        .thenReturn(
            Optional.of(
                new StoredObject(
                    900,
                    "video/mp4",
                    Map.of(
                        "watermarked", "true",
                        "videoId", request.videoId().toString(),
                        "durationMs", "60000"))));

    JobResult result = job.execute(request);

    assertThat(result.alreadyDone()).isTrue();
    assertThat(result.durationMs()).isEqualTo(60_000);
    verify(storage).delete(request.rawObjectKey());
    verify(processor, never()).watermark(any(), any(), any(), any());
  }

  @Test
  void happyPathStoresCreateOnlyThenDeletesRaw() {
    givenRawExists(4_000);
    when(ffprobe.probe(any(), any()))
        .thenReturn(new MediaInfo(true, 1280, 720, null))
        .thenReturn(new MediaInfo(true, 1280, 720, 60.0));
    givenProcessingWritesOutput();
    when(storage.createIfAbsent(eq(request.finalObjectKey()), any(), eq("video/mp4"), anyMap()))
        .thenReturn(true);

    JobResult result = job.execute(request);

    assertThat(result.alreadyDone()).isFalse();
    assertThat(result.durationMs()).isEqualTo(60_000);
    verify(storage).delete(request.rawObjectKey());
    assertThat(tmp).isEmptyDirectory();
  }

  @Test
  void lostCreateRaceIsTreatedAsDone() {
    givenRawExists(4_000);
    when(ffprobe.probe(any(), any())).thenReturn(new MediaInfo(true, 1280, 720, 60.0));
    givenProcessingWritesOutput();
    when(storage.createIfAbsent(anyString(), any(), anyString(), anyMap())).thenReturn(false);

    assertThat(job.execute(request).alreadyDone()).isTrue();
  }

  @Test
  void missingSourceIsPermanent() {
    when(storage.stat(anyString())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> job.execute(request))
        .isInstanceOf(NonRetryableProcessingException.class);
  }

  @Test
  void oversizeSourceIsRejectedBeforeDownload() {
    givenRawExists(200_000_000);
    assertThatThrownBy(() -> job.execute(request))
        .isInstanceOf(NonRetryableProcessingException.class)
        .extracting(ex -> ((NonRetryableProcessingException) ex).code())
        .isEqualTo(FailureCode.TOO_LARGE);
    verify(storage, never()).download(anyString(), any());
  }

  @Test
  void videoWithoutVideoStreamIsCorrupt() {
    givenRawExists(4_000);
    when(ffprobe.probe(any(), any())).thenReturn(new MediaInfo(false, 0, 0, null));
    assertThatThrownBy(() -> job.execute(request))
        .extracting(ex -> ((NonRetryableProcessingException) ex).code())
        .isEqualTo(FailureCode.CORRUPT_INPUT);
    verify(storage, never()).delete(anyString());
  }

  @Test
  void tooLongOutputIsPermanent() {
    givenRawExists(4_000);
    when(ffprobe.probe(any(), any()))
        .thenReturn(new MediaInfo(true, 1280, 720, null))
        .thenReturn(new MediaInfo(true, 1280, 720, 200.0));
    givenProcessingWritesOutput();
    assertThatThrownBy(() -> job.execute(request))
        .extracting(ex -> ((NonRetryableProcessingException) ex).code())
        .isEqualTo(FailureCode.TOO_LONG);
  }

  @Test
  void encoderFailureIsRetryableAndKeepsRaw() {
    givenRawExists(4_000);
    when(ffprobe.probe(any(), any())).thenReturn(new MediaInfo(true, 1280, 720, 60.0));
    doThrow(new RetryableProcessingException("ffmpeg exited with code 1"))
        .when(processor)
        .watermark(any(), any(), any(), any());

    assertThatThrownBy(() -> job.execute(request)).isInstanceOf(RetryableProcessingException.class);
    verify(storage, never()).delete(anyString());
    assertThat(tmp).isEmptyDirectory();
  }

  @Test
  void rawDeleteFailureDoesNotFailTheJob() {
    givenRawExists(4_000);
    when(ffprobe.probe(any(), any())).thenReturn(new MediaInfo(true, 1280, 720, 60.0));
    givenProcessingWritesOutput();
    when(storage.createIfAbsent(anyString(), any(), anyString(), anyMap())).thenReturn(true);
    doThrow(new RetryableProcessingException("GCS delete failed"))
        .when(storage)
        .delete(request.rawObjectKey());

    assertThat(job.execute(request).alreadyDone()).isFalse();
  }
}

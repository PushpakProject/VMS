package com.vms.watermark.application;

import com.vms.watermark.config.WatermarkProperties;
import com.vms.watermark.exception.FailureCode;
import com.vms.watermark.exception.NonRetryableProcessingException;
import com.vms.watermark.exception.RetryableProcessingException;
import com.vms.watermark.processing.FfprobeClient;
import com.vms.watermark.processing.MediaInfo;
import com.vms.watermark.processing.VideoProcessor;
import com.vms.watermark.storage.ObjectStoragePort;
import com.vms.watermark.storage.ObjectStoragePort.StoredObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Adds the logo bottom-right: raw object in, watermarked MP4 out, raw object removed. */
@Component
public class WatermarkJob extends AbstractVideoJob<MediaInfo> {

  static final String META_WATERMARKED = "watermarked";
  static final String META_VIDEO_ID = "videoId";
  static final String META_DURATION_MS = "durationMs";
  private static final String OUTPUT_TYPE = "video/mp4";
  private static final double DURATION_DRIFT_SEC = 1.5;
  private static final Logger log = LoggerFactory.getLogger(WatermarkJob.class);

  private final ObjectStoragePort storage;
  private final FfprobeClient ffprobe;
  private final VideoProcessor processor;
  private final WatermarkProperties properties;

  public WatermarkJob(
      ObjectStoragePort storage,
      FfprobeClient ffprobe,
      VideoProcessor processor,
      WatermarkProperties properties) {
    this.storage = storage;
    this.ffprobe = ffprobe;
    this.processor = processor;
    this.properties = properties;
  }

  @Override
  protected Path workDir() {
    return Path.of(properties.workDir());
  }

  @Override
  protected Optional<JobResult> findCompletedOutput(JobRequest request) {
    return storage
        .stat(request.finalObjectKey())
        .filter(o -> "true".equals(o.metadata().get(META_WATERMARKED)))
        .filter(o -> request.videoId().toString().equals(o.metadata().get(META_VIDEO_ID)))
        .map(o -> toResult(request, o, true));
  }

  @Override
  protected Path fetchSource(JobRequest request, TempWorkspace workspace) {
    StoredObject raw =
        storage
            .stat(request.rawObjectKey())
            .orElseThrow(
                () ->
                    new NonRetryableProcessingException(
                        FailureCode.PROCESSING_FAILED, "Source object not found"));
    if (raw.sizeBytes() > properties.maxInputBytes()) {
      throw new NonRetryableProcessingException(FailureCode.TOO_LARGE, "Source exceeds size limit");
    }
    Path input = workspace.file("input" + extension(request.contentType()));
    storage.download(request.rawObjectKey(), input);
    return input;
  }

  @Override
  protected MediaInfo validateInput(JobRequest request, Path input, TempWorkspace workspace) {
    MediaInfo info = ffprobe.probe(input, workspace.dir());
    if (!info.hasVideo() || info.width() <= 0) {
      throw new NonRetryableProcessingException(FailureCode.CORRUPT_INPUT, "No video stream");
    }
    if (info.durationSec() != null && info.durationSec() > properties.maxAcceptedDurationSec()) {
      throw new NonRetryableProcessingException(FailureCode.TOO_LONG, "Source exceeds duration");
    }
    return info;
  }

  @Override
  protected Path transform(
      JobRequest request, Path input, MediaInfo inputInfo, TempWorkspace workspace) {
    Path output = workspace.file("output.mp4");
    long started = System.nanoTime();
    processor.watermark(input, inputInfo, output, workspace.dir());
    log.info(
        "Watermarked video {} in {} ms",
        request.videoId(),
        (System.nanoTime() - started) / 1_000_000);
    return output;
  }

  @Override
  protected MediaInfo verifyOutput(
      JobRequest request, Path output, MediaInfo inputInfo, TempWorkspace workspace) {
    MediaInfo info = ffprobe.probe(output, workspace.dir());
    if (!info.hasVideo() || info.durationSec() == null) {
      throw new RetryableProcessingException("Output has no playable video stream");
    }
    if (info.durationSec() > properties.maxAcceptedDurationSec()) {
      throw new NonRetryableProcessingException(FailureCode.TOO_LONG, "Recording exceeds duration");
    }
    if (inputInfo.durationSec() != null
        && Math.abs(info.durationSec() - inputInfo.durationSec()) > DURATION_DRIFT_SEC) {
      throw new RetryableProcessingException("Output duration differs from input");
    }
    return info;
  }

  @Override
  protected JobResult storeOutput(JobRequest request, Path output, MediaInfo outputInfo) {
    Map<String, String> metadata = new HashMap<>();
    metadata.put(META_WATERMARKED, "true");
    metadata.put(META_VIDEO_ID, request.videoId().toString());
    metadata.put(META_DURATION_MS, String.valueOf(outputInfo.durationMs()));
    boolean created =
        storage.createIfAbsent(request.finalObjectKey(), output, OUTPUT_TYPE, metadata);
    if (!created) {
      log.info("Output for video {} already written by another attempt", request.videoId());
    }
    return new JobResult(
        request.finalObjectKey(), sizeOf(output), outputInfo.durationMs(), !created);
  }

  @Override
  protected void removeSource(JobRequest request) {
    try {
      storage.delete(request.rawObjectKey());
    } catch (RuntimeException ex) {
      log.warn("Could not delete raw object for video {}; lifecycle rule will", request.videoId());
    }
  }

  private static JobResult toResult(JobRequest request, StoredObject object, boolean alreadyDone) {
    Integer duration = null;
    String value = object.metadata().get(META_DURATION_MS);
    if (value != null && !"null".equals(value)) {
      duration = Integer.valueOf(value);
    }
    return new JobResult(request.finalObjectKey(), object.sizeBytes(), duration, alreadyDone);
  }

  private static String extension(String contentType) {
    return "video/mp4".equals(contentType) ? ".mp4" : ".webm";
  }

  private static long sizeOf(Path file) {
    try {
      return Files.size(file);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }
}

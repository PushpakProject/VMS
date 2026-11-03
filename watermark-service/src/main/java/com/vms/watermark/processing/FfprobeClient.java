package com.vms.watermark.processing;

import com.vms.watermark.config.WatermarkProperties;
import com.vms.watermark.exception.FailureCode;
import com.vms.watermark.exception.NonRetryableProcessingException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads stream/format metadata with {@code ffprobe -print_format json}. */
@Component
public class FfprobeClient {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final ProcessRunner runner;
  private final WatermarkProperties properties;
  private final JsonMapper jsonMapper;

  public FfprobeClient(
      ProcessRunner runner, WatermarkProperties properties, JsonMapper jsonMapper) {
    this.runner = runner;
    this.properties = properties;
    this.jsonMapper = jsonMapper;
  }

  public MediaInfo probe(Path file, Path workDir) {
    Path stdout = workDir.resolve(file.getFileName() + ".probe.json");
    Path stderr = workDir.resolve(file.getFileName() + ".probe.err");
    List<String> command =
        List.of(
            properties.ffprobePath(),
            "-v",
            "error",
            "-print_format",
            "json",
            "-show_format",
            "-show_streams",
            file.toString());
    ProcessRunner.Result result = runner.run(command, TIMEOUT, stdout, stderr);
    if (!result.succeeded()) {
      throw new NonRetryableProcessingException(
          FailureCode.CORRUPT_INPUT, "ffprobe could not read the file");
    }
    try {
      return parse(jsonMapper.readTree(Files.readString(stdout)));
    } catch (IOException | RuntimeException ex) {
      throw new NonRetryableProcessingException(
          FailureCode.CORRUPT_INPUT, "ffprobe output could not be parsed");
    }
  }

  static MediaInfo parse(JsonNode root) {
    JsonNode video = null;
    for (JsonNode stream : root.path("streams")) {
      if ("video".equals(stream.path("codec_type").asString(""))) {
        video = stream;
        break;
      }
    }
    if (video == null) {
      return new MediaInfo(false, 0, 0, null);
    }
    Double duration = parseDuration(root.path("format").path("duration").asString(""));
    if (duration == null) {
      duration = parseDuration(video.path("duration").asString(""));
    }
    return new MediaInfo(
        true, video.path("width").asInt(0), video.path("height").asInt(0), duration);
  }

  private static Double parseDuration(String value) {
    try {
      double seconds = Double.parseDouble(value);
      return seconds > 0 ? seconds : null;
    } catch (NumberFormatException ex) {
      return null;
    }
  }
}

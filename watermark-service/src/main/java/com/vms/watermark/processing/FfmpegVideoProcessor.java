package com.vms.watermark.processing;

import com.vms.watermark.config.WatermarkProperties;
import com.vms.watermark.exception.RetryableProcessingException;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FfmpegVideoProcessor implements VideoProcessor {

  private static final Logger log = LoggerFactory.getLogger(FfmpegVideoProcessor.class);

  private final ProcessRunner runner;
  private final WatermarkProperties properties;

  public FfmpegVideoProcessor(ProcessRunner runner, WatermarkProperties properties) {
    this.runner = runner;
    this.properties = properties;
  }

  @Override
  public void watermark(Path input, MediaInfo inputInfo, Path output, Path workDir) {
    List<String> command =
        FfmpegCommandBuilder.create()
            .ffmpeg(properties.ffmpegPath())
            .input(input)
            .logo(Path.of(properties.logoPath()))
            .output(output)
            .videoWidth(inputInfo.width())
            .logoWidthRatio(properties.logoWidthRatio())
            .logoOpacity(properties.logoOpacity())
            .preset(properties.preset())
            .crf(properties.crf())
            .threads(properties.threads())
            .build();
    ProcessRunner.Result result =
        runner.run(
            command,
            properties.timeout(),
            workDir.resolve("ffmpeg.out"),
            workDir.resolve("ffmpeg.err"));
    if (!result.succeeded()) {
      log.warn("ffmpeg exited with {}: {}", result.exitCode(), result.stderrTail());
      throw new RetryableProcessingException("ffmpeg exited with code " + result.exitCode());
    }
  }
}

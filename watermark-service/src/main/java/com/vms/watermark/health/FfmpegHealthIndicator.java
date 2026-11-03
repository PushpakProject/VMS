package com.vms.watermark.health;

import com.vms.watermark.config.WatermarkProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** The worker is only healthy if it can actually watermark: binaries and logo must be present. */
@Component("ffmpeg")
public class FfmpegHealthIndicator implements HealthIndicator {

  private final WatermarkProperties properties;

  public FfmpegHealthIndicator(WatermarkProperties properties) {
    this.properties = properties;
  }

  @Override
  public Health health() {
    boolean logo = Files.isReadable(Path.of(properties.logoPath()));
    boolean ffmpeg = isExecutable(properties.ffmpegPath());
    boolean ffprobe = isExecutable(properties.ffprobePath());
    Health.Builder builder = logo && ffmpeg && ffprobe ? Health.up() : Health.down();
    return builder
        .withDetail("logo", logo)
        .withDetail("ffmpeg", ffmpeg)
        .withDetail("ffprobe", ffprobe)
        .build();
  }

  private static boolean isExecutable(String binary) {
    Path path = Path.of(binary);
    if (path.isAbsolute()) {
      return Files.isExecutable(path);
    }
    String pathEnv = System.getenv("PATH");
    if (pathEnv == null) {
      return false;
    }
    for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
      if (Files.isExecutable(Path.of(dir, binary))) {
        return true;
      }
    }
    return false;
  }
}

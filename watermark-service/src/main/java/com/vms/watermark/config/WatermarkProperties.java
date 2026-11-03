package com.vms.watermark.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Watermark processing settings; validated at startup so bad config fails fast. */
@Validated
@ConfigurationProperties("vms.watermark")
public record WatermarkProperties(
    @NotBlank String ffmpegPath,
    @NotBlank String ffprobePath,
    @NotBlank String logoPath,
    @NotBlank String workDir,
    @NotNull Duration timeout,
    @Min(1) int maxAttempts,
    @Min(10) int maxDurationSec,
    @Min(0) int durationToleranceSec,
    @Min(1_000_000) long maxInputBytes,
    @DecimalMin("0.05") @DecimalMax("0.5") double logoWidthRatio,
    @DecimalMin("0.1") @DecimalMax("1.0") double logoOpacity,
    @NotBlank String preset,
    @Min(0) int crf,
    @Min(1) int threads) {

  public double maxAcceptedDurationSec() {
    return maxDurationSec + durationToleranceSec;
  }
}

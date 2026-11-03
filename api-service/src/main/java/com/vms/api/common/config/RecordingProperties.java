package com.vms.api.common.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Single source of truth for recording and upload limits. Exposed to the browser through {@code GET
 * /api/v1/config/public} so client and server can never disagree.
 */
@Validated
@ConfigurationProperties("vms.recording")
public record RecordingProperties(
    @Min(10) int maxDurationSec,
    @Min(1) int warnAtSec,
    @Min(0) int durationToleranceSec,
    @Min(1_000_000) long maxUploadBytes,
    @NotEmpty List<String> allowedContentTypes,
    @Min(100_000) int videoBitsPerSecond,
    @Min(160) int videoWidth,
    @Min(120) int videoHeight,
    @NotNull Duration uploadUrlTtl,
    @NotNull Duration playbackUrlTtl) {

  public long maxAcceptedDurationMs() {
    return (long) (maxDurationSec + durationToleranceSec) * 1000;
  }

  /**
   * Strips codec parameters, e.g. {@code video/webm;codecs=vp9,opus} becomes {@code video/webm}.
   */
  public static String baseContentType(String contentType) {
    if (contentType == null) {
      return "";
    }
    int separator = contentType.indexOf(';');
    String base = separator >= 0 ? contentType.substring(0, separator) : contentType;
    return base.trim().toLowerCase(Locale.ROOT);
  }

  public boolean isAllowedContentType(String contentType) {
    return allowedContentTypes.contains(baseContentType(contentType));
  }
}

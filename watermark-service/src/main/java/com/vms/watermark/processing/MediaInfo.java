package com.vms.watermark.processing;

/**
 * What ffprobe tells us about a file. {@code durationSec} is null when the container doesn't store
 * it (browser-recorded WebM often doesn't).
 */
public record MediaInfo(boolean hasVideo, int width, int height, Double durationSec) {

  public Integer durationMs() {
    return durationSec == null ? null : (int) Math.round(durationSec * 1000);
  }
}

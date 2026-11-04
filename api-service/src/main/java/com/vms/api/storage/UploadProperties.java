package com.vms.api.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Upload protocol. {@code RESUMABLE} (default): the API opens a storage-side resumable session and
 * the browser sends {@code chunkSizeBytes} pieces, resuming from the last byte storage confirmed.
 * {@code SIMPLE}: one size-capped signed PUT per clip.
 */
@ConfigurationProperties("vms.upload")
public record UploadProperties(Protocol protocol, Integer chunkSizeBytes, Duration sessionTtl) {

  /** GCS requires non-final chunks to be multiples of 256 KiB. */
  public static final int CHUNK_GRANULARITY = 256 * 1024;

  public enum Protocol {
    SIMPLE,
    RESUMABLE
  }

  public UploadProperties {
    protocol = protocol == null ? Protocol.RESUMABLE : protocol;
    chunkSizeBytes = chunkSizeBytes == null ? 16 * CHUNK_GRANULARITY : chunkSizeBytes;
    sessionTtl = sessionTtl == null ? Duration.ofDays(6) : sessionTtl;
    if (chunkSizeBytes <= 0 || chunkSizeBytes % CHUNK_GRANULARITY != 0) {
      throw new IllegalStateException("vms.upload.chunk-size-bytes must be a multiple of 256 KiB");
    }
  }
}

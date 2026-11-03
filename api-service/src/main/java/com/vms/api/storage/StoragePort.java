package com.vms.api.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Port to object storage (hexagonal architecture). The video module depends on this interface,
 * never on GCS classes, so storage can be swapped (local emulator, S3) without touching it.
 */
public interface StoragePort {

  /**
   * Pre-signed upload for one object. The signature binds method, object, content type, expiry and
   * the allowed size range, so the browser cannot upload anything else.
   */
  UploadTarget createUploadTarget(String key, String contentType, long maxBytes, Duration ttl);

  SignedUrl createDownloadUrl(String key, Duration ttl);

  Optional<ObjectStat> stat(String key);

  void delete(String key);

  record UploadTarget(String url, String method, Map<String, String> headers, Instant expiresAt) {}

  record SignedUrl(String url, Instant expiresAt) {}

  record ObjectStat(long sizeBytes, String contentType) {}
}

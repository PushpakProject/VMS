package com.vms.watermark.storage;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Port to object storage. Implementations: GCS (prod) and a shared-volume emulator (local). */
public interface ObjectStoragePort {

  Optional<StoredObject> stat(String key);

  void download(String key, Path target);

  /**
   * Creates the object only if it does not exist yet (GCS precondition {@code
   * ifGenerationMatch=0}).
   *
   * @return {@code false} if another worker already created it
   */
  boolean createIfAbsent(String key, Path source, String contentType, Map<String, String> metadata);

  void delete(String key);

  record StoredObject(long sizeBytes, String contentType, Map<String, String> metadata) {}
}

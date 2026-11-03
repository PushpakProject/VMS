package com.vms.watermark.storage;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.vms.watermark.exception.RetryableProcessingException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public class GcsObjectStorageAdapter implements ObjectStoragePort {

  private static final int PRECONDITION_FAILED = 412;

  private final Storage storage;
  private final String bucket;

  public GcsObjectStorageAdapter(Storage storage, String bucket) {
    this.storage = storage;
    this.bucket = bucket;
  }

  @Override
  public Optional<StoredObject> stat(String key) {
    try {
      Blob blob = storage.get(BlobId.of(bucket, key));
      if (blob == null) {
        return Optional.empty();
      }
      Map<String, String> metadata = blob.getMetadata() == null ? Map.of() : blob.getMetadata();
      return Optional.of(new StoredObject(blob.getSize(), blob.getContentType(), metadata));
    } catch (StorageException ex) {
      throw new RetryableProcessingException("GCS stat failed (" + ex.getCode() + ")", ex);
    }
  }

  @Override
  public void download(String key, Path target) {
    try {
      storage.downloadTo(BlobId.of(bucket, key), target);
    } catch (StorageException ex) {
      throw new RetryableProcessingException("GCS download failed (" + ex.getCode() + ")", ex);
    }
  }

  @Override
  public boolean createIfAbsent(
      String key, Path source, String contentType, Map<String, String> metadata) {
    BlobInfo info =
        BlobInfo.newBuilder(BlobId.of(bucket, key))
            .setContentType(contentType)
            .setMetadata(metadata)
            .build();
    try {
      storage.createFrom(info, source, Storage.BlobWriteOption.doesNotExist());
      return true;
    } catch (StorageException ex) {
      if (ex.getCode() == PRECONDITION_FAILED) {
        return false;
      }
      throw new RetryableProcessingException("GCS upload failed (" + ex.getCode() + ")", ex);
    } catch (IOException ex) {
      throw new RetryableProcessingException("GCS upload failed", ex);
    }
  }

  @Override
  public void delete(String key) {
    try {
      storage.delete(BlobId.of(bucket, key));
    } catch (StorageException ex) {
      throw new RetryableProcessingException("GCS delete failed (" + ex.getCode() + ")", ex);
    }
  }
}

package com.vms.api.storage.gcs;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.SignUrlOption;
import com.google.cloud.storage.StorageException;
import com.vms.api.common.error.ApiException;
import com.vms.api.common.error.ErrorCode;
import com.vms.api.storage.StoragePort;
import com.vms.api.storage.UploadProperties;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * GCS adapter. Signing is a local V4 computation; on GCE the VM's service account signs through the
 * IAM signBlob API, so no private key file exists anywhere.
 */
public class GcsStorageAdapter implements StoragePort {

  static final String SIZE_RANGE_HEADER = "x-goog-content-length-range";

  private static final Duration INIT_URL_TTL = Duration.ofMinutes(5);

  private final Storage storage;
  private final String bucket;
  private final Clock clock;
  private final ResumableSessionStarter sessionStarter;
  private final String browserOrigin;

  /**
   * @param browserOrigin sent as {@code Origin} when opening resumable sessions: GCS applies the
   *     bucket CORS rules for that origin to the browser's later PUTs on the session URI
   */
  public GcsStorageAdapter(
      Storage storage,
      String bucket,
      Clock clock,
      ResumableSessionStarter sessionStarter,
      String browserOrigin) {
    this.storage = storage;
    this.bucket = bucket;
    this.clock = clock;
    this.sessionStarter = sessionStarter;
    this.browserOrigin = browserOrigin;
  }

  @Override
  public UploadTarget createUploadTarget(
      String key, String contentType, long maxBytes, Duration ttl) {
    Map<String, String> headers =
        Map.of("Content-Type", contentType, SIZE_RANGE_HEADER, "0," + maxBytes);
    URL url =
        sign(
            key,
            ttl,
            SignUrlOption.httpMethod(HttpMethod.PUT),
            SignUrlOption.withV4Signature(),
            SignUrlOption.withExtHeaders(headers));
    return new UploadTarget(
        url.toString(),
        "PUT",
        headers,
        clock.instant().plus(ttl),
        UploadProperties.Protocol.SIMPLE);
  }

  /**
   * The API (not the browser) opens the session: it POSTs a short-lived signed URL with {@code
   * x-goog-resumable: start} and {@code X-Upload-Content-Length}, so GCS enforces the declared
   * size. The browser only receives the session URI.
   */
  @Override
  public UploadTarget createResumableUpload(
      String key, String contentType, long sizeBytes, Duration sessionTtl) {
    Map<String, String> signed = Map.of("Content-Type", contentType, "x-goog-resumable", "start");
    URL init =
        sign(
            key,
            INIT_URL_TTL,
            SignUrlOption.httpMethod(HttpMethod.POST),
            SignUrlOption.withV4Signature(),
            SignUrlOption.withExtHeaders(signed));
    Map<String, String> request = new HashMap<>(signed);
    request.put("X-Upload-Content-Length", Long.toString(sizeBytes));
    if (browserOrigin != null && !browserOrigin.isBlank()) {
      request.put("Origin", browserOrigin);
    }
    String sessionUri;
    try {
      sessionUri = sessionStarter.start(init.toURI(), request);
    } catch (URISyntaxException ex) {
      throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
    }
    return new UploadTarget(
        sessionUri,
        "PUT",
        Map.of(),
        clock.instant().plus(sessionTtl),
        UploadProperties.Protocol.RESUMABLE);
  }

  @Override
  public SignedUrl createDownloadUrl(String key, Duration ttl) {
    URL url =
        sign(key, ttl, SignUrlOption.httpMethod(HttpMethod.GET), SignUrlOption.withV4Signature());
    return new SignedUrl(url.toString(), clock.instant().plus(ttl));
  }

  @Override
  public Optional<ObjectStat> stat(String key) {
    try {
      Blob blob = storage.get(BlobId.of(bucket, key));
      return blob == null
          ? Optional.empty()
          : Optional.of(new ObjectStat(blob.getSize(), blob.getContentType()));
    } catch (StorageException ex) {
      throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
    }
  }

  @Override
  public void delete(String key) {
    try {
      storage.delete(BlobId.of(bucket, key));
    } catch (StorageException ex) {
      throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
    }
  }

  private URL sign(String key, Duration ttl, SignUrlOption... options) {
    try {
      return storage.signUrl(
          BlobInfo.newBuilder(BlobId.of(bucket, key)).build(),
          ttl.toSeconds(),
          TimeUnit.SECONDS,
          options);
    } catch (StorageException | IllegalStateException ex) {
      throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
    }
  }
}

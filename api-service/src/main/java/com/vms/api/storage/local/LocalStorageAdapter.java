package com.vms.api.storage.local;

import com.vms.api.storage.StoragePort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Local-development stand-in for GCS. Objects live on a shared volume (also mounted into the
 * watermark service); URLs are HMAC-signed and served by {@link LocalStorageController}.
 */
public class LocalStorageAdapter implements StoragePort {

  static final String BASE_PATH = "/api/v1/local-storage/objects/";
  static final String CONTENT_TYPE_SUFFIX = ".content-type";

  private final Path root;
  private final LocalUrlSigner signer;
  private final Clock clock;

  public LocalStorageAdapter(Path root, LocalUrlSigner signer, Clock clock) {
    this.root = root.toAbsolutePath().normalize();
    this.signer = signer;
    this.clock = clock;
  }

  @Override
  public UploadTarget createUploadTarget(
      String key, String contentType, long maxBytes, Duration ttl) {
    SignedUrl signed = signedUrl("PUT", key, ttl, contentType, maxBytes);
    return new UploadTarget(
        signed.url(), "PUT", Map.of("Content-Type", contentType), signed.expiresAt());
  }

  @Override
  public SignedUrl createDownloadUrl(String key, Duration ttl) {
    return signedUrl("GET", key, ttl, "", 0);
  }

  @Override
  public Optional<ObjectStat> stat(String key) {
    Path file = resolve(key);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      Path typeFile = file.resolveSibling(file.getFileName() + CONTENT_TYPE_SUFFIX);
      String type =
          Files.exists(typeFile) ? Files.readString(typeFile) : "application/octet-stream";
      return Optional.of(new ObjectStat(Files.size(file), type));
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  @Override
  public void delete(String key) {
    Path file = resolve(key);
    try {
      Files.deleteIfExists(file);
      Files.deleteIfExists(file.resolveSibling(file.getFileName() + CONTENT_TYPE_SUFFIX));
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  /** Resolves a key under the root and rejects anything that escapes it. */
  public Path resolve(String key) {
    Path file = root.resolve(key).normalize();
    if (!file.startsWith(root) || file.equals(root)) {
      throw new IllegalArgumentException("Invalid object key");
    }
    return file;
  }

  public LocalUrlSigner signer() {
    return signer;
  }

  public Instant now() {
    return clock.instant();
  }

  private SignedUrl signedUrl(
      String method, String key, Duration ttl, String contentType, long maxBytes) {
    long expires = clock.instant().plus(ttl).getEpochSecond();
    String signature = signer.sign(method, key, expires, contentType, maxBytes);
    String url =
        BASE_PATH
            + key
            + "?exp="
            + expires
            + "&max="
            + maxBytes
            + "&ct="
            + URLEncoder.encode(contentType, StandardCharsets.UTF_8)
            + "&sig="
            + signature;
    return new SignedUrl(url, Instant.ofEpochSecond(expires));
  }
}

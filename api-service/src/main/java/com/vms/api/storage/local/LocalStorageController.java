package com.vms.api.storage.local;

import com.vms.api.common.config.RecordingProperties;
import com.vms.api.common.error.ApiException;
import com.vms.api.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the local storage emulator's signed PUT/GET URLs. Only active with {@code
 * vms.storage.provider=local}; in production uploads go straight to GCS.
 */
@RestController
@RequestMapping(LocalStorageAdapter.BASE_PATH)
@ConditionalOnProperty(name = "vms.storage.provider", havingValue = "local")
public class LocalStorageController {

  private final LocalStorageAdapter storage;

  public LocalStorageController(LocalStorageAdapter storage) {
    this.storage = storage;
  }

  @PutMapping("**")
  public ResponseEntity<Void> upload(
      HttpServletRequest request,
      @RequestParam("exp") long expires,
      @RequestParam("max") long maxBytes,
      @RequestParam("ct") String contentType,
      @RequestParam("sig") String signature,
      @RequestHeader(value = "Content-Type", required = false) String requestType)
      throws IOException {
    String key = keyOf(request);
    verify(signature, "PUT", key, expires, contentType, maxBytes);
    if (!RecordingProperties.baseContentType(contentType)
        .equals(RecordingProperties.baseContentType(requestType))) {
      throw new ApiException(ErrorCode.FORBIDDEN, "Content-Type does not match the signature.");
    }
    if (request.getContentLengthLong() > maxBytes) {
      throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
    }
    Path target = storage.resolve(key);
    Files.createDirectories(target.getParent());
    Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
    try (InputStream in = request.getInputStream()) {
      long written = copyLimited(in, temp, maxBytes);
      if (written > maxBytes) {
        throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
      }
      Files.writeString(
          target.resolveSibling(target.getFileName() + LocalStorageAdapter.CONTENT_TYPE_SUFFIX),
          contentType);
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temp);
    }
    return ResponseEntity.ok().build();
  }

  @GetMapping("**")
  public ResponseEntity<Resource> download(
      HttpServletRequest request,
      @RequestParam("exp") long expires,
      @RequestParam("sig") String signature) {
    String key = keyOf(request);
    verify(signature, "GET", key, expires, "", 0);
    Path file = storage.resolve(key);
    if (!Files.isRegularFile(file)) {
      throw new ApiException(ErrorCode.NOT_FOUND);
    }
    MediaType type =
        MediaTypeFactory.getMediaType(file.getFileName().toString())
            .orElse(MediaType.APPLICATION_OCTET_STREAM);
    return ResponseEntity.ok().contentType(type).body(new FileSystemResource(file));
  }

  private void verify(
      String signature, String method, String key, long expires, String contentType, long max) {
    boolean valid = storage.signer().verify(signature, method, key, expires, contentType, max);
    if (!valid || storage.now().getEpochSecond() > expires) {
      throw new ApiException(ErrorCode.FORBIDDEN, "The signed URL is invalid or expired.");
    }
  }

  private static String keyOf(HttpServletRequest request) {
    return request.getRequestURI().substring(LocalStorageAdapter.BASE_PATH.length());
  }

  private static long copyLimited(InputStream in, Path target, long maxBytes) throws IOException {
    byte[] buffer = new byte[64 * 1024];
    long total = 0;
    try (var out = Files.newOutputStream(target)) {
      int read;
      while ((read = in.read(buffer)) != -1) {
        total += read;
        if (total > maxBytes) {
          return total;
        }
        out.write(buffer, 0, read);
      }
    }
    return total;
  }
}

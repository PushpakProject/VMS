package com.vms.api.storage.local;

import com.vms.api.common.error.ApiException;
import com.vms.api.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP face of {@link LocalResumableSessions} (local profile only). */
@RestController
@RequestMapping(LocalResumableSessions.BASE_PATH)
@ConditionalOnProperty(name = "vms.storage.provider", havingValue = "local")
public class LocalResumableController {

  private final LocalStorageAdapter storage;

  public LocalResumableController(LocalStorageAdapter storage) {
    this.storage = storage;
  }

  @PutMapping("{sessionId}")
  public ResponseEntity<Void> put(
      @PathVariable String sessionId,
      @RequestHeader(value = "Content-Range", required = false) String contentRange,
      HttpServletRequest request)
      throws IOException {
    LocalResumableSessions.Result result =
        storage
            .sessions()
            .handle(
                sessionId, contentRange, request.getContentLengthLong(), request.getInputStream());
    return switch (result.status()) {
      case 200 -> ResponseEntity.ok().build();
      case 308 -> {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(308);
        if (result.committedBytes() > 0) {
          builder.header("Range", "bytes=0-" + (result.committedBytes() - 1));
        }
        yield builder.build();
      }
      case 404 ->
          throw new ApiException(ErrorCode.NOT_FOUND, "Upload session not found or expired.");
      default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "Invalid Content-Range.");
    };
  }
}

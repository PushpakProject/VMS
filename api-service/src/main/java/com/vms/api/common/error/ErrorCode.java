package com.vms.api.common.error;

import org.springframework.http.HttpStatus;

/** Stable, client-facing error codes. Messages never contain internal details. */
public enum ErrorCode {
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "The request is invalid."),
  MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "The request body could not be read."),
  INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid username or password."),
  INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Your session has expired. Please sign in again."),
  UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication is required."),
  FORBIDDEN(HttpStatus.FORBIDDEN, "You are not allowed to perform this action."),
  NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource was not found."),
  ACCOUNT_EXISTS(HttpStatus.CONFLICT, "An account with this email or username already exists."),
  INVALID_STATE(HttpStatus.CONFLICT, "The video is not in a state that allows this action."),
  DUPLICATE_REQUEST(HttpStatus.CONFLICT, "The request is already being processed. Please retry."),
  UPLOAD_NOT_FOUND(HttpStatus.CONFLICT, "The uploaded file was not found in storage."),
  NOT_READY(HttpStatus.CONFLICT, "The video is still being processed."),
  RAW_NOT_AVAILABLE(HttpStatus.CONFLICT, "The original recording is no longer available."),
  NOT_RETRYABLE(HttpStatus.CONFLICT, "This failure cannot be retried."),
  PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "The video exceeds the maximum allowed size."),
  UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "This video format is not supported."),
  STORAGE_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE, "Storage is temporarily unavailable. Please retry."),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");

  private final HttpStatus status;
  private final String defaultMessage;

  ErrorCode(HttpStatus status, String defaultMessage) {
    this.status = status;
    this.defaultMessage = defaultMessage;
  }

  public HttpStatus status() {
    return status;
  }

  public String defaultMessage() {
    return defaultMessage;
  }
}

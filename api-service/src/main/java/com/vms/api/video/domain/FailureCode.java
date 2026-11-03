package com.vms.api.video.domain;

/**
 * Why a video failed. {@code retryable} decides whether the owner may retry: bad input will fail
 * again, transient processing failures may not.
 */
public enum FailureCode {
  TOO_LARGE(false, "The video exceeds the maximum allowed size."),
  TOO_LONG(false, "The video exceeds the maximum allowed duration."),
  CORRUPT_INPUT(false, "The recording could not be read."),
  UNSUPPORTED_FORMAT(false, "The recording format is not supported."),
  PROCESSING_FAILED(true, "Watermarking failed after several attempts.");

  private final boolean retryable;
  private final String message;

  FailureCode(boolean retryable, String message) {
    this.retryable = retryable;
    this.message = message;
  }

  public boolean retryable() {
    return retryable;
  }

  public String message() {
    return message;
  }

  /** Lenient parsing of codes received from other services. */
  public static FailureCode fromWire(String value) {
    if (value != null) {
      for (FailureCode code : values()) {
        if (code.name().equals(value)) {
          return code;
        }
      }
    }
    return PROCESSING_FAILED;
  }
}

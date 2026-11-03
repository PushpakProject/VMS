package com.vms.watermark.exception;

/** Base processing failure carrying a contract-level {@link FailureCode}. */
public abstract class ProcessingException extends RuntimeException {

  private final FailureCode code;

  protected ProcessingException(FailureCode code, String detail, Throwable cause) {
    super("[" + code + "] " + detail, cause);
    this.code = code;
  }

  public FailureCode code() {
    return code;
  }
}

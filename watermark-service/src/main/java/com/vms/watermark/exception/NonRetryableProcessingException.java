package com.vms.watermark.exception;

/** Permanent failure (bad input, limits exceeded): skips retries and goes straight to the DLT. */
public class NonRetryableProcessingException extends ProcessingException {

  public NonRetryableProcessingException(FailureCode code, String detail) {
    super(code, detail, null);
  }
}

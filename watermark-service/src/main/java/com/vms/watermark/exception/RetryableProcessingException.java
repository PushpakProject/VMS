package com.vms.watermark.exception;

/** Transient failure (storage 5xx, timeout, encoder crash): goes through the retry topics. */
public class RetryableProcessingException extends ProcessingException {

  public RetryableProcessingException(String detail) {
    this(detail, null);
  }

  public RetryableProcessingException(String detail, Throwable cause) {
    super(FailureCode.PROCESSING_FAILED, detail, cause);
  }
}

package com.vms.watermark.exception;

/** Failure codes shared with the API over the result-event contract (by name). */
public enum FailureCode {
  TOO_LARGE,
  TOO_LONG,
  CORRUPT_INPUT,
  UNSUPPORTED_FORMAT,
  PROCESSING_FAILED
}

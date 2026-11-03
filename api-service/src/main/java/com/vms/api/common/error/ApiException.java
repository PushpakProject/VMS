package com.vms.api.common.error;

/** Business exception carrying a stable {@link ErrorCode}; mapped to RFC 7807 by the handler. */
public class ApiException extends RuntimeException {

  private final ErrorCode code;

  public ApiException(ErrorCode code) {
    this(code, code.defaultMessage());
  }

  public ApiException(ErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public ErrorCode code() {
    return code;
  }
}

package com.vms.api.video.domain;

import com.vms.api.common.error.ApiException;
import com.vms.api.common.error.ErrorCode;
import java.util.regex.Pattern;

/** Value object: a validated, immutable Tracking ID. */
public record TrackingId(String value) {

  public static final String REGEX = "^[A-Za-z0-9_-]{3,64}$";
  private static final Pattern PATTERN = Pattern.compile(REGEX);

  public TrackingId {
    if (value == null || !PATTERN.matcher(value).matches()) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "Tracking ID format is invalid.");
    }
  }

  public static TrackingId of(String value) {
    return new TrackingId(value == null ? null : value.trim());
  }
}

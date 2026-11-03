package com.vms.api.auth.api;

import com.vms.api.auth.infrastructure.AuthProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Builds the refresh-token cookie: httpOnly (unreadable by JS), Secure in prod, SameSite=Strict and
 * scoped to the auth path so it is never sent with other API calls.
 */
@Component
public class RefreshCookies {

  public static final String NAME = "vms_rt";
  public static final String PATH = "/api/v1/auth";

  private final AuthProperties properties;

  public RefreshCookies(AuthProperties properties) {
    this.properties = properties;
  }

  public ResponseCookie create(String value, long maxAgeSeconds) {
    return base(value).maxAge(maxAgeSeconds).build();
  }

  public ResponseCookie clear() {
    return base("").maxAge(0).build();
  }

  private ResponseCookie.ResponseCookieBuilder base(String value) {
    return ResponseCookie.from(NAME, value)
        .httpOnly(true)
        .secure(properties.cookieSecure())
        .sameSite("Strict")
        .path(PATH);
  }
}

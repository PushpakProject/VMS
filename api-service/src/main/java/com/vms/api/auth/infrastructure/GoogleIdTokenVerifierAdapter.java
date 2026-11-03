package com.vms.api.auth.infrastructure;

import com.vms.api.auth.port.GoogleIdentity;
import com.vms.api.auth.port.GoogleTokenVerifierPort;
import com.vms.api.common.error.ApiException;
import com.vms.api.common.error.ErrorCode;
import java.util.List;
import java.util.Set;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Verifies Google ID tokens against Google's published keys: signature (RS256), issuer, audience
 * (our client id), expiry and verified email. The browser's claims are never trusted.
 */
@Component
public class GoogleIdTokenVerifierAdapter implements GoogleTokenVerifierPort {

  private static final Set<String> ISSUERS =
      Set.of("accounts.google.com", "https://accounts.google.com");

  private final GoogleAuthProperties properties;
  private volatile JwtDecoder decoder;

  public GoogleIdTokenVerifierAdapter(GoogleAuthProperties properties) {
    this.properties = properties;
  }

  @Override
  public GoogleIdentity verify(String idToken) {
    if (!properties.enabled()) {
      throw new ApiException(ErrorCode.NOT_FOUND, "Google sign-in is not enabled.");
    }
    try {
      Jwt jwt = decoder().decode(idToken);
      if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
        throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Google account email not verified.");
      }
      String name = jwt.getClaimAsString("name");
      return new GoogleIdentity(jwt.getSubject(), jwt.getClaimAsString("email"), name);
    } catch (JwtException ex) {
      throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Google sign-in failed.");
    }
  }

  private JwtDecoder decoder() {
    JwtDecoder current = decoder;
    if (current == null) {
      synchronized (this) {
        if (decoder == null) {
          NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri()).build();
          nimbus.setJwtValidator(
              new DelegatingOAuth2TokenValidator<>(
                  new JwtTimestampValidator(), issuerValidator(), audienceValidator()));
          decoder = nimbus;
        }
        current = decoder;
      }
    }
    return current;
  }

  private static OAuth2TokenValidator<Jwt> issuerValidator() {
    return jwt ->
        ISSUERS.contains(jwt.getClaimAsString("iss"))
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_issuer"));
  }

  private OAuth2TokenValidator<Jwt> audienceValidator() {
    return jwt -> {
      List<String> audience = jwt.getAudience();
      return audience != null && audience.contains(properties.clientId())
          ? OAuth2TokenValidatorResult.success()
          : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_audience"));
    };
  }
}

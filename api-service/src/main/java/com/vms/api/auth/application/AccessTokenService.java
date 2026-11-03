package com.vms.api.auth.application;

import com.vms.api.auth.domain.User;
import com.vms.api.auth.infrastructure.AuthProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues short-lived access tokens. The client keeps them in memory only. */
@Service
public class AccessTokenService {

  public static final String ROLES_CLAIM = "roles";

  private final JwtEncoder encoder;
  private final AuthProperties properties;
  private final Clock clock;

  public AccessTokenService(JwtEncoder encoder, AuthProperties properties, Clock clock) {
    this.encoder = encoder;
    this.properties = properties;
    this.clock = clock;
  }

  public IssuedAccessToken issue(User user) {
    Instant now = clock.instant();
    Instant expiresAt = now.plus(properties.accessTokenTtl());
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .id(UUID.randomUUID().toString())
            .issuer(properties.jwtIssuer())
            .subject(user.getId().toString())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .claim(ROLES_CLAIM, List.of(user.getRole().name()))
            .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return new IssuedAccessToken(token, properties.accessTokenTtl().toSeconds());
  }

  public record IssuedAccessToken(String value, long expiresInSeconds) {}
}

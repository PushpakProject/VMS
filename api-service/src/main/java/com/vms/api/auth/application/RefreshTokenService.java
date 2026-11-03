package com.vms.api.auth.application;

import com.vms.api.auth.domain.RefreshToken;
import com.vms.api.auth.domain.RefreshToken.RevokeReason;
import com.vms.api.auth.infrastructure.AuthProperties;
import com.vms.api.auth.infrastructure.RefreshTokenRepository;
import com.vms.api.common.error.ApiException;
import com.vms.api.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Refresh-token rotation with reuse detection. Every refresh replaces the token. Presenting an
 * already-rotated token (outside a short grace window for concurrent tabs) means it was probably
 * stolen, so the whole family is revoked.
 */
@Service
public class RefreshTokenService {

  private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
  private static final int TOKEN_BYTES = 32;

  private final RefreshTokenRepository tokens;
  private final AuthProperties properties;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public RefreshTokenService(
      RefreshTokenRepository tokens, AuthProperties properties, Clock clock) {
    this.tokens = tokens;
    this.properties = properties;
    this.clock = clock;
  }

  public IssuedRefreshToken issueNewFamily(UUID userId) {
    return issue(userId, UUID.randomUUID());
  }

  /** Validates and rotates the presented token. Caller must run inside a transaction. */
  public Rotation rotate(String rawToken) {
    Instant now = clock.instant();
    RefreshToken current =
        tokens
            .findByTokenHash(hash(rawToken))
            .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REFRESH_TOKEN));
    if (current.isRevoked()) {
      if (current.isWithinRotationGrace(now, properties.refreshRotationGrace())) {
        return new Rotation(current.getUserId(), issue(current.getUserId(), current.getFamilyId()));
      }
      tokens.revokeFamily(current.getFamilyId(), RevokeReason.REUSE_DETECTED, now);
      log.warn("Refresh token reuse detected; token family revoked");
      throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN);
    }
    if (current.isExpired(now)) {
      throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN);
    }
    current.revoke(RevokeReason.ROTATED, now);
    tokens.save(current);
    return new Rotation(current.getUserId(), issue(current.getUserId(), current.getFamilyId()));
  }

  public void revokeFamilyOf(String rawToken) {
    tokens
        .findByTokenHash(hash(rawToken))
        .ifPresent(t -> tokens.revokeFamily(t.getFamilyId(), RevokeReason.LOGOUT, clock.instant()));
  }

  private IssuedRefreshToken issue(UUID userId, UUID familyId) {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant now = clock.instant();
    tokens.save(
        new RefreshToken(userId, hash(raw), familyId, now.plus(properties.refreshTokenTtl()), now));
    return new IssuedRefreshToken(raw, properties.refreshTokenTtl().toSeconds());
  }

  static String hash(String raw) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  public record IssuedRefreshToken(String value, long maxAgeSeconds) {}

  public record Rotation(UUID userId, IssuedRefreshToken token) {}
}

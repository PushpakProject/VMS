package com.vms.api.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A refresh token in a rotation family. Only the SHA-256 hash is stored; the raw value lives in an
 * httpOnly cookie.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

  public enum RevokeReason {
    ROTATED,
    LOGOUT,
    REUSE_DETECTED
  }

  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "token_hash", nullable = false, unique = true)
  private String tokenHash;

  @Column(name = "family_id", nullable = false)
  private UUID familyId;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "revoke_reason")
  private RevokeReason revokeReason;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected RefreshToken() {}

  public RefreshToken(
      UUID userId, String tokenHash, UUID familyId, Instant expiresAt, Instant createdAt) {
    this.id = UUID.randomUUID();
    this.userId = userId;
    this.tokenHash = tokenHash;
    this.familyId = familyId;
    this.expiresAt = expiresAt;
    this.createdAt = createdAt;
  }

  public boolean isExpired(Instant now) {
    return !expiresAt.isAfter(now);
  }

  public boolean isRevoked() {
    return revokedAt != null;
  }

  /** True when this token was rotated very recently, e.g. two tabs refreshing at once. */
  public boolean isWithinRotationGrace(Instant now, Duration grace) {
    return revokeReason == RevokeReason.ROTATED && revokedAt.plus(grace).isAfter(now);
  }

  public void revoke(RevokeReason reason, Instant now) {
    if (revokedAt == null) {
      revokedAt = now;
      revokeReason = reason;
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getTokenHash() {
    return tokenHash;
  }

  public UUID getFamilyId() {
    return familyId;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }
}

package com.vms.api.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

  @Id private UUID id;

  @Column(nullable = false, unique = true)
  private String email;

  @Column(unique = true)
  private String username;

  @Column(name = "password_hash")
  private String passwordHash;

  @Column(name = "display_name", nullable = false)
  private String displayName;

  @Enumerated(EnumType.STRING)
  @Column(name = "auth_provider", nullable = false)
  private AuthProvider authProvider;

  @Column(name = "google_subject", unique = true)
  private String googleSubject;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Role role;

  @Column(nullable = false)
  private boolean enabled;

  @Column(name = "failed_login_attempts", nullable = false)
  private int failedLoginAttempts;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version private long version;

  protected User() {}

  public static User withPassword(
      String email,
      String username,
      String displayName,
      String passwordHash,
      Role role,
      Instant now) {
    User user = base(email, displayName, role, AuthProvider.PASSWORD, now);
    user.username = normalize(username);
    user.passwordHash = passwordHash;
    return user;
  }

  public static User withGoogle(
      String email, String displayName, String googleSubject, Instant now) {
    User user = base(email, displayName, Role.USER, AuthProvider.GOOGLE, now);
    user.googleSubject = googleSubject;
    return user;
  }

  private static User base(
      String email, String displayName, Role role, AuthProvider provider, Instant now) {
    User user = new User();
    user.id = UUID.randomUUID();
    user.email = normalize(email);
    user.displayName = displayName;
    user.role = role;
    user.authProvider = provider;
    user.enabled = true;
    user.createdAt = now;
    user.updatedAt = now;
    return user;
  }

  public static String normalize(String value) {
    return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
  }

  public boolean isLocked(Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }

  /** Counts a failed password attempt and locks the account once the threshold is reached. */
  public void recordFailedLogin(Instant now, int maxAttempts, Duration lockDuration) {
    failedLoginAttempts++;
    if (failedLoginAttempts >= maxAttempts) {
      lockedUntil = now.plus(lockDuration);
      failedLoginAttempts = 0;
    }
    updatedAt = now;
  }

  public void recordSuccessfulLogin(Instant now) {
    failedLoginAttempts = 0;
    lockedUntil = null;
    updatedAt = now;
  }

  public void linkGoogle(String subject, Instant now) {
    this.googleSubject = subject;
    this.updatedAt = now;
  }

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getUsername() {
    return username;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getDisplayName() {
    return displayName;
  }

  public AuthProvider getAuthProvider() {
    return authProvider;
  }

  public String getGoogleSubject() {
    return googleSubject;
  }

  public Role getRole() {
    return role;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public int getFailedLoginAttempts() {
    return failedLoginAttempts;
  }

  public Instant getLockedUntil() {
    return lockedUntil;
  }
}

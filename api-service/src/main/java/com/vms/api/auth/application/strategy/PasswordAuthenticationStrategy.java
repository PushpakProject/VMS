package com.vms.api.auth.application.strategy;

import com.vms.api.auth.domain.User;
import com.vms.api.auth.infrastructure.AuthProperties;
import com.vms.api.auth.infrastructure.UserRepository;
import com.vms.api.common.error.ApiException;
import com.vms.api.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Username/email + password. Unknown users, wrong passwords and locked accounts all return the same
 * generic error, and a dummy hash is checked for unknown users so response timing doesn't reveal
 * which accounts exist.
 */
@Component
public class PasswordAuthenticationStrategy
    implements AuthenticationStrategy<Credentials.Password> {

  /** Audit trail: account ids only, never the submitted login name or password. */
  private static final Logger audit = LoggerFactory.getLogger("vms.audit.auth");

  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final AuthProperties properties;
  private final Clock clock;
  private final String dummyHash;

  public PasswordAuthenticationStrategy(
      UserRepository users,
      PasswordEncoder passwordEncoder,
      AuthProperties properties,
      Clock clock) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.properties = properties;
    this.clock = clock;
    this.dummyHash = passwordEncoder.encode("timing-equalisation-placeholder");
  }

  @Override
  public Class<Credentials.Password> supports() {
    return Credentials.Password.class;
  }

  @Override
  public User authenticate(Credentials.Password credentials) {
    Instant now = clock.instant();
    User user = users.findByLogin(User.normalize(credentials.login())).orElse(null);
    if (user == null || user.getPasswordHash() == null) {
      passwordEncoder.matches(credentials.password(), dummyHash);
      audit.warn("Login failed: unknown account");
      throw invalid();
    }
    if (!user.isEnabled() || user.isLocked(now)) {
      audit.warn("Login refused: account {} is locked or disabled", user.getId());
      throw invalid();
    }
    if (!passwordEncoder.matches(credentials.password(), user.getPasswordHash())) {
      user.recordFailedLogin(now, properties.maxFailedLogins(), properties.lockDuration());
      users.save(user);
      if (user.isLocked(now)) {
        audit.warn("Account {} locked after repeated failed logins", user.getId());
      } else {
        audit.warn("Login failed: wrong password for account {}", user.getId());
      }
      throw invalid();
    }
    user.recordSuccessfulLogin(now);
    audit.info("Login succeeded for account {}", user.getId());
    return users.save(user);
  }

  private static ApiException invalid() {
    return new ApiException(ErrorCode.INVALID_CREDENTIALS);
  }
}

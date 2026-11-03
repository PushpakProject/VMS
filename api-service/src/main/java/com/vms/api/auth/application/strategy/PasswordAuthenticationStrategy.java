package com.vms.api.auth.application.strategy;

import com.vms.api.auth.domain.User;
import com.vms.api.auth.infrastructure.AuthProperties;
import com.vms.api.auth.infrastructure.UserRepository;
import com.vms.api.common.error.ApiException;
import com.vms.api.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
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
      throw invalid();
    }
    if (!user.isEnabled() || user.isLocked(now)) {
      throw invalid();
    }
    if (!passwordEncoder.matches(credentials.password(), user.getPasswordHash())) {
      user.recordFailedLogin(now, properties.maxFailedLogins(), properties.lockDuration());
      users.save(user);
      throw invalid();
    }
    user.recordSuccessfulLogin(now);
    return users.save(user);
  }

  private static ApiException invalid() {
    return new ApiException(ErrorCode.INVALID_CREDENTIALS);
  }
}

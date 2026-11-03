package com.vms.api.auth.infrastructure;

import com.vms.api.auth.domain.Role;
import com.vms.api.auth.domain.User;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the reviewer test accounts from environment variables (never hardcoded). Existing
 * accounts are left untouched.
 */
@Component
public class DemoUserBootstrap implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DemoUserBootstrap.class);

  private final DemoUsersProperties properties;
  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final Clock clock;

  public DemoUserBootstrap(
      DemoUsersProperties properties,
      UserRepository users,
      PasswordEncoder passwordEncoder,
      Clock clock) {
    this.properties = properties;
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.clock = clock;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    ensure(properties.userEmail(), properties.userPassword(), "Demo Operator", Role.USER);
    ensure(properties.adminEmail(), properties.adminPassword(), "Demo Admin", Role.ADMIN);
  }

  private void ensure(String email, String password, String displayName, Role role) {
    if (isBlank(email) || isBlank(password) || email.indexOf('@') < 1) {
      return;
    }
    if (users.findByEmail(User.normalize(email)).isPresent()) {
      return;
    }
    String username = email.substring(0, email.indexOf('@'));
    users.save(
        User.withPassword(
            email, username, displayName, passwordEncoder.encode(password), role, clock.instant()));
    log.info("Created demo account with role {}", role);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  @ConfigurationProperties("vms.demo")
  public record DemoUsersProperties(
      String userEmail, String userPassword, String adminEmail, String adminPassword) {}
}

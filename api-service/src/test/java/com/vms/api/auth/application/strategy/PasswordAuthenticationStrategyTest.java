package com.vms.api.auth.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vms.api.auth.domain.Role;
import com.vms.api.auth.domain.User;
import com.vms.api.auth.infrastructure.AuthProperties;
import com.vms.api.auth.infrastructure.UserRepository;
import com.vms.api.common.error.ApiException;
import com.vms.api.common.error.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordAuthenticationStrategyTest {

  private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");
  private final UserRepository users = mock(UserRepository.class);
  private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
  private PasswordAuthenticationStrategy strategy;
  private User user;

  @BeforeEach
  void setUp() {
    AuthProperties properties =
        new AuthProperties(
            "x",
            "vms-api",
            Duration.ofMinutes(15),
            Duration.ofDays(7),
            Duration.ofSeconds(10),
            true,
            List.of(),
            5,
            Duration.ofMinutes(15));
    strategy =
        new PasswordAuthenticationStrategy(
            users, encoder, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    user =
        User.withPassword(
            "op@example.com",
            "op",
            "Operator",
            encoder.encode("correct-horse-battery"),
            Role.USER,
            NOW);
    when(users.findByLogin("op@example.com")).thenReturn(Optional.of(user));
    when(users.findByLogin("op")).thenReturn(Optional.of(user));
    when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void acceptsEmailOrUsernameCaseInsensitively() {
    assertThat(
            strategy.authenticate(
                new Credentials.Password("OP@Example.com", "correct-horse-battery")))
        .isSameAs(user);
    assertThat(strategy.authenticate(new Credentials.Password("op", "correct-horse-battery")))
        .isSameAs(user);
  }

  @Test
  void locksAfterFiveFailuresAndHidesTheReason() {
    for (int i = 0; i < 5; i++) {
      assertInvalid(new Credentials.Password("op", "wrong"));
    }
    assertThat(user.isLocked(NOW)).isTrue();
    assertInvalid(new Credentials.Password("op", "correct-horse-battery"));
  }

  @Test
  void unknownUserGetsTheSameError() {
    when(users.findByLogin("ghost")).thenReturn(Optional.empty());
    assertInvalid(new Credentials.Password("ghost", "whatever"));
  }

  private void assertInvalid(Credentials.Password credentials) {
    assertThatThrownBy(() -> strategy.authenticate(credentials))
        .isInstanceOf(ApiException.class)
        .extracting(ex -> ((ApiException) ex).code())
        .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
  }
}

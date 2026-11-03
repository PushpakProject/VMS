package com.vms.api.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vms.api.auth.domain.RefreshToken;
import com.vms.api.auth.domain.RefreshToken.RevokeReason;
import com.vms.api.auth.infrastructure.AuthProperties;
import com.vms.api.auth.infrastructure.RefreshTokenRepository;
import com.vms.api.common.error.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RefreshTokenServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");

  private final RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
  private final Map<String, RefreshToken> byHash = new HashMap<>();
  private final MutableClock clock = new MutableClock(NOW);
  private RefreshTokenService service;
  private final UUID userId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    AuthProperties properties =
        new AuthProperties(
            "c2VjcmV0LXNlY3JldC1zZWNyZXQtc2VjcmV0LXNlY3JldA==",
            "vms-api",
            Duration.ofMinutes(15),
            Duration.ofDays(7),
            Duration.ofSeconds(10),
            true,
            List.of(),
            5,
            Duration.ofMinutes(15));
    service = new RefreshTokenService(repository, properties, clock);
    when(repository.save(any(RefreshToken.class)))
        .thenAnswer(
            inv -> {
              RefreshToken token = inv.getArgument(0);
              byHash.put(token.getTokenHash(), token);
              return token;
            });
    when(repository.findByTokenHash(any()))
        .thenAnswer(inv -> Optional.ofNullable(byHash.get((String) inv.getArgument(0))));
  }

  private String issueAndTrack() {
    return service.issueNewFamily(userId).value();
  }

  @Test
  void rotationRevokesOldTokenAndIssuesNewOne() {
    String raw = issueAndTrack();

    var rotation = service.rotate(raw);

    assertThat(rotation.userId()).isEqualTo(userId);
    assertThat(rotation.token().value()).isNotEqualTo(raw);
    assertThat(byHash.get(RefreshTokenService.hash(raw)).isRevoked()).isTrue();
  }

  @Test
  void reuseAfterGraceRevokesWholeFamily() {
    String raw = issueAndTrack();
    service.rotate(raw);
    clock.advance(Duration.ofSeconds(30));

    assertThatThrownBy(() -> service.rotate(raw)).isInstanceOf(ApiException.class);
    verify(repository).revokeFamily(any(), eq(RevokeReason.REUSE_DETECTED), any());
  }

  @Test
  void concurrentRefreshWithinGraceIsTolerated() {
    String raw = issueAndTrack();
    service.rotate(raw);
    clock.advance(Duration.ofSeconds(2));

    var second = service.rotate(raw);

    assertThat(second.userId()).isEqualTo(userId);
  }

  @Test
  void unknownTokenIsRejected() {
    assertThatThrownBy(() -> service.rotate("nope")).isInstanceOf(ApiException.class);
  }

  @Test
  void onlyHashIsStored() {
    String raw = issueAndTrack();
    assertThat(byHash).doesNotContainKey(raw);
    assertThat(RefreshTokenService.hash(raw)).hasSize(64);
  }

  static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}

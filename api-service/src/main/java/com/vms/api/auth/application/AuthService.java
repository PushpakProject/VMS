package com.vms.api.auth.application;

import com.vms.api.auth.application.strategy.AuthenticationStrategyResolver;
import com.vms.api.auth.application.strategy.Credentials;
import com.vms.api.auth.domain.Role;
import com.vms.api.auth.domain.User;
import com.vms.api.auth.infrastructure.UserRepository;
import com.vms.api.common.error.ApiException;
import com.vms.api.common.error.ErrorCode;
import java.time.Clock;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Orchestrates sign-in, registration, refresh and sign-out. */
@Service
public class AuthService {

  private final AuthenticationStrategyResolver strategies;
  private final AccessTokenService accessTokens;
  private final RefreshTokenService refreshTokens;
  private final UserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final Clock clock;

  public AuthService(
      AuthenticationStrategyResolver strategies,
      AccessTokenService accessTokens,
      RefreshTokenService refreshTokens,
      UserRepository users,
      PasswordEncoder passwordEncoder,
      Clock clock) {
    this.strategies = strategies;
    this.accessTokens = accessTokens;
    this.refreshTokens = refreshTokens;
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.clock = clock;
  }

  /** Failed-attempt counters must be persisted even though the login itself fails. */
  @Transactional(noRollbackFor = ApiException.class)
  public AuthResult login(Credentials credentials) {
    User user = strategies.resolve(credentials).authenticate(credentials);
    return issueTokens(user, refreshTokens.issueNewFamily(user.getId()));
  }

  @Transactional
  public AuthResult register(String email, String username, String displayName, String password) {
    if (users.existsByEmailOrUsername(User.normalize(email), User.normalize(username))) {
      throw new ApiException(ErrorCode.ACCOUNT_EXISTS);
    }
    User user =
        users.save(
            User.withPassword(
                email,
                username,
                displayName,
                passwordEncoder.encode(password),
                Role.USER,
                clock.instant()));
    return issueTokens(user, refreshTokens.issueNewFamily(user.getId()));
  }

  @Transactional(noRollbackFor = ApiException.class)
  public AuthResult refresh(String rawRefreshToken) {
    RefreshTokenService.Rotation rotation = refreshTokens.rotate(rawRefreshToken);
    User user = findActiveUser(rotation.userId());
    return issueTokens(user, rotation.token());
  }

  @Transactional
  public void logout(String rawRefreshToken) {
    refreshTokens.revokeFamilyOf(rawRefreshToken);
  }

  @Transactional(readOnly = true)
  public User currentUser(UUID userId) {
    return findActiveUser(userId);
  }

  private User findActiveUser(UUID userId) {
    return users
        .findById(userId)
        .filter(User::isEnabled)
        .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REFRESH_TOKEN));
  }

  private AuthResult issueTokens(User user, RefreshTokenService.IssuedRefreshToken refreshToken) {
    return new AuthResult(user, accessTokens.issue(user), refreshToken);
  }

  public record AuthResult(
      User user,
      AccessTokenService.IssuedAccessToken accessToken,
      RefreshTokenService.IssuedRefreshToken refreshToken) {}
}

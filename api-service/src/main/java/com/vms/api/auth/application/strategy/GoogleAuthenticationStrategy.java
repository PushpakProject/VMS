package com.vms.api.auth.application.strategy;

import com.vms.api.auth.domain.User;
import com.vms.api.auth.infrastructure.UserRepository;
import com.vms.api.auth.port.GoogleIdentity;
import com.vms.api.auth.port.GoogleTokenVerifierPort;
import com.vms.api.common.error.ApiException;
import com.vms.api.common.error.ErrorCode;
import java.time.Clock;
import org.springframework.stereotype.Component;

/**
 * Google SSO: verify the ID token server-side, then find the user by Google subject, link an
 * existing account with the same verified email, or create a new one.
 */
@Component
public class GoogleAuthenticationStrategy
    implements AuthenticationStrategy<Credentials.GoogleIdToken> {

  private final GoogleTokenVerifierPort verifier;
  private final UserRepository users;
  private final Clock clock;

  public GoogleAuthenticationStrategy(
      GoogleTokenVerifierPort verifier, UserRepository users, Clock clock) {
    this.verifier = verifier;
    this.users = users;
    this.clock = clock;
  }

  @Override
  public Class<Credentials.GoogleIdToken> supports() {
    return Credentials.GoogleIdToken.class;
  }

  @Override
  public User authenticate(Credentials.GoogleIdToken credentials) {
    GoogleIdentity identity = verifier.verify(credentials.idToken());
    User user =
        users
            .findByGoogleSubject(identity.subject())
            .or(() -> users.findByEmail(User.normalize(identity.email())))
            .orElseGet(
                () ->
                    User.withGoogle(
                        identity.email(),
                        identity.name() == null ? identity.email() : identity.name(),
                        identity.subject(),
                        clock.instant()));
    if (!user.isEnabled()) {
      throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
    }
    if (user.getGoogleSubject() == null) {
      user.linkGoogle(identity.subject(), clock.instant());
    }
    return users.save(user);
  }
}

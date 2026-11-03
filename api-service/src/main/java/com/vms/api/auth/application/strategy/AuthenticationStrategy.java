package com.vms.api.auth.application.strategy;

import com.vms.api.auth.domain.User;

/**
 * Strategy pattern: one implementation per credential type. Adding a provider (e.g. Microsoft)
 * means adding a class, not editing existing login code.
 */
public interface AuthenticationStrategy<C extends Credentials> {

  Class<C> supports();

  /**
   * @return the authenticated user
   * @throws com.vms.api.common.error.ApiException with {@code INVALID_CREDENTIALS} on failure
   */
  User authenticate(C credentials);
}

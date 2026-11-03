package com.vms.api.auth.application.strategy;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Factory that picks the strategy matching the credential type. */
@Component
public class AuthenticationStrategyResolver {

  private final Map<Class<?>, AuthenticationStrategy<?>> strategies;

  public AuthenticationStrategyResolver(List<AuthenticationStrategy<?>> strategies) {
    this.strategies =
        strategies.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    AuthenticationStrategy::supports, Function.identity()));
  }

  @SuppressWarnings("unchecked")
  public <C extends Credentials> AuthenticationStrategy<C> resolve(C credentials) {
    AuthenticationStrategy<?> strategy = strategies.get(credentials.getClass());
    if (strategy == null) {
      throw new IllegalArgumentException(
          "No strategy for " + credentials.getClass().getSimpleName());
    }
    return (AuthenticationStrategy<C>) strategy;
  }
}

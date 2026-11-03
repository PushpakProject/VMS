package com.vms.api.auth.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Google Sign-In is optional: it is enabled only when a client id is configured. */
@ConfigurationProperties("vms.auth.google")
public record GoogleAuthProperties(String clientId, String jwkSetUri) {

  public boolean enabled() {
    return clientId != null && !clientId.isBlank();
  }
}

package com.vms.api.auth.port;

/** Port for verifying a Google ID token; keeps the auth domain independent of Google libraries. */
public interface GoogleTokenVerifierPort {

  GoogleIdentity verify(String idToken);
}

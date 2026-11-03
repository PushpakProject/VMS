package com.vms.api.auth.application.strategy;

/** The different ways a user can prove who they are. Each type has one strategy. */
public sealed interface Credentials {

  record Password(String login, String password) implements Credentials {}

  record GoogleIdToken(String idToken) implements Credentials {}
}

package com.vms.api.common.security;

import java.util.UUID;

/** The authenticated caller, resolved from the access token for controller methods. */
public record CurrentUser(UUID id, boolean admin) {}

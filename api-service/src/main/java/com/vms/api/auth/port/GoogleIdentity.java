package com.vms.api.auth.port;

public record GoogleIdentity(String subject, String email, String name) {}

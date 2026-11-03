package com.vms.api.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code provider=gcs} in production; {@code provider=local} uses an HMAC-signed filesystem
 * emulator so the full flow runs without cloud credentials.
 */
@ConfigurationProperties("vms.storage")
public record StorageProperties(
    String provider, String bucket, String localDir, String localSigningKey) {}

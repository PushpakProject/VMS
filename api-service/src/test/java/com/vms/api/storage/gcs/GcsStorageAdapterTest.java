package com.vms.api.storage.gcs;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.vms.api.storage.StoragePort.UploadTarget;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Signing is a local computation, so this runs offline with a throwaway key. */
class GcsStorageAdapterTest {

  @Test
  void uploadUrlSignsContentTypeAndSizeRange() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    RSAPrivateKey key = (RSAPrivateKey) generator.generateKeyPair().getPrivate();
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail("signer@test-project.iam.gserviceaccount.com")
            .setPrivateKey(key)
            .setProjectId("test-project")
            .build();
    Storage storage =
        StorageOptions.newBuilder()
            .setProjectId("test-project")
            .setCredentials(credentials)
            .build()
            .getService();
    Instant now = Instant.parse("2026-11-03T10:00:00Z");
    GcsStorageAdapter adapter =
        new GcsStorageAdapter(storage, "vms-bucket", Clock.fixed(now, ZoneOffset.UTC));

    UploadTarget target =
        adapter.createUploadTarget(
            "raw/u/v.webm", "video/webm", 104_857_600, Duration.ofMinutes(15));

    String url = URLDecoder.decode(target.url(), StandardCharsets.UTF_8);
    assertThat(url).startsWith("https://storage.googleapis.com/vms-bucket/raw/u/v.webm?");
    assertThat(url).contains("X-Goog-Algorithm=GOOG4-RSA-SHA256");
    assertThat(url).contains("X-Goog-Expires=900");
    assertThat(url).contains("X-Goog-SignedHeaders=content-type;host;x-goog-content-length-range");
    assertThat(target.method()).isEqualTo("PUT");
    assertThat(target.headers())
        .containsEntry("Content-Type", "video/webm")
        .containsEntry("x-goog-content-length-range", "0,104857600");
    assertThat(target.expiresAt()).isEqualTo(now.plusSeconds(900));
  }
}

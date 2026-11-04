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
        new GcsStorageAdapter(
            storage,
            "vms-bucket",
            Clock.fixed(now, ZoneOffset.UTC),
            (url, headers) -> {
              throw new AssertionError("not used");
            },
            "https://app.example");

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

  @Test
  void resumableSessionIsOpenedServerSideWithDeclaredSizeAndOrigin() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    ServiceAccountCredentials credentials =
        ServiceAccountCredentials.newBuilder()
            .setClientEmail("signer@test-project.iam.gserviceaccount.com")
            .setPrivateKey((RSAPrivateKey) generator.generateKeyPair().getPrivate())
            .setProjectId("test-project")
            .build();
    Storage storage =
        StorageOptions.newBuilder()
            .setProjectId("test-project")
            .setCredentials(credentials)
            .build()
            .getService();
    Instant now = Instant.parse("2026-11-03T10:00:00Z");
    java.util.concurrent.atomic.AtomicReference<java.util.Map<String, String>> sent =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicReference<java.net.URI> initUrl =
        new java.util.concurrent.atomic.AtomicReference<>();
    GcsStorageAdapter adapter =
        new GcsStorageAdapter(
            storage,
            "vms-bucket",
            Clock.fixed(now, ZoneOffset.UTC),
            (url, headers) -> {
              initUrl.set(url);
              sent.set(headers);
              return "https://storage.googleapis.com/upload/session-123";
            },
            "https://app.example");

    UploadTarget target =
        adapter.createResumableUpload("raw/u/v.webm", "video/webm", 41_000_000, Duration.ofDays(6));

    String signed = URLDecoder.decode(initUrl.get().toString(), StandardCharsets.UTF_8);
    assertThat(signed).contains("X-Goog-SignedHeaders=content-type;host;x-goog-resumable");
    assertThat(sent.get())
        .containsEntry("x-goog-resumable", "start")
        .containsEntry("X-Upload-Content-Length", "41000000")
        .containsEntry("Origin", "https://app.example");
    assertThat(target.url()).isEqualTo("https://storage.googleapis.com/upload/session-123");
    assertThat(target.protocol().name()).isEqualTo("RESUMABLE");
    assertThat(target.headers()).isEmpty();
  }
}

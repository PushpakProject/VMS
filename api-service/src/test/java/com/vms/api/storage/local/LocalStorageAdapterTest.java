package com.vms.api.storage.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalStorageAdapterTest {

  private static final String KEY = "0123456789abcdef0123456789abcdef";

  @TempDir Path root;

  @Test
  void rejectsPathTraversal() {
    LocalStorageAdapter adapter =
        new LocalStorageAdapter(root, new LocalUrlSigner(KEY), Clock.systemUTC());
    assertThatThrownBy(() -> adapter.resolve("../outside.txt"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> adapter.resolve("raw/../../x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(adapter.resolve("raw/a/b.webm").startsWith(root.toAbsolutePath().normalize()))
        .isTrue();
  }

  @Test
  void signatureCoversEveryField() {
    LocalUrlSigner signer = new LocalUrlSigner(KEY);
    String sig = signer.sign("PUT", "raw/a.webm", 100, "video/webm", 10);

    assertThat(signer.verify(sig, "PUT", "raw/a.webm", 100, "video/webm", 10)).isTrue();
    assertThat(signer.verify(sig, "GET", "raw/a.webm", 100, "video/webm", 10)).isFalse();
    assertThat(signer.verify(sig, "PUT", "raw/b.webm", 100, "video/webm", 10)).isFalse();
    assertThat(signer.verify(sig, "PUT", "raw/a.webm", 101, "video/webm", 10)).isFalse();
    assertThat(signer.verify(sig, "PUT", "raw/a.webm", 100, "video/mp4", 10)).isFalse();
    assertThat(signer.verify(sig, "PUT", "raw/a.webm", 100, "video/webm", 11)).isFalse();
  }

  @Test
  void uploadUrlCarriesExpiry() {
    Instant now = Instant.parse("2026-11-03T10:00:00Z");
    LocalStorageAdapter adapter =
        new LocalStorageAdapter(root, new LocalUrlSigner(KEY), Clock.fixed(now, ZoneOffset.UTC));
    var target =
        adapter.createUploadTarget(
            "raw/a.webm", "video/webm", 10, java.time.Duration.ofMinutes(15));
    assertThat(target.url()).startsWith("/api/v1/local-storage/objects/raw/a.webm?exp=");
    assertThat(target.expiresAt()).isEqualTo(now.plusSeconds(900));
  }
}

package com.vms.api.storage.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalResumableSessionsTest {

  private static final int PIECE = 256 * 1024;

  @TempDir Path root;

  private LocalStorageAdapter adapter() {
    return new LocalStorageAdapter(
        root, new LocalUrlSigner("0123456789abcdef0123456789abcdef"), Clock.systemUTC());
  }

  private static LocalResumableSessions.Result put(
      LocalResumableSessions sessions, String id, String range, byte[] body) {
    return sessions.handle(id, range, body.length, new ByteArrayInputStream(body));
  }

  @Test
  void resumesFromTheCommittedOffsetAndFinalizesTheObject() throws Exception {
    LocalStorageAdapter adapter = adapter();
    int total = PIECE + 1000;
    String url =
        adapter
            .createResumableUpload("raw/u/v.webm", "video/webm", total, Duration.ofDays(1))
            .url();
    String id = url.substring(url.lastIndexOf('/') + 1);
    LocalResumableSessions sessions = adapter.sessions();

    assertThat(put(sessions, id, "bytes */" + total, new byte[0]).status()).isEqualTo(308);
    var first = put(sessions, id, "bytes 0-" + (PIECE - 1) + "/" + total, new byte[PIECE]);
    assertThat(first.status()).isEqualTo(308);
    assertThat(first.committedBytes()).isEqualTo(PIECE);

    // "Network drop": the client lost the response, asks again and resumes from the server offset.
    assertThat(put(sessions, id, "bytes */" + total, new byte[0]).committedBytes())
        .isEqualTo(PIECE);
    // A stale retry of the first piece is answered with the real offset, not written twice.
    assertThat(
            put(sessions, id, "bytes 0-" + (PIECE - 1) + "/" + total, new byte[PIECE])
                .committedBytes())
        .isEqualTo(PIECE);

    var last =
        put(sessions, id, "bytes " + PIECE + "-" + (total - 1) + "/" + total, new byte[1000]);
    assertThat(last.status()).isEqualTo(200);
    assertThat(Files.size(root.resolve("raw/u/v.webm"))).isEqualTo(total);
    assertThat(adapter.stat("raw/u/v.webm").orElseThrow().contentType()).isEqualTo("video/webm");
    assertThat(put(sessions, id, "bytes */" + total, new byte[0]).status()).isEqualTo(404);
  }

  @Test
  void rejectsMisalignedPiecesWrongTotalsAndUnknownSessions() {
    LocalStorageAdapter adapter = adapter();
    String url =
        adapter
            .createResumableUpload("raw/u/x.webm", "video/webm", 3 * PIECE, Duration.ofDays(1))
            .url();
    String id = url.substring(url.lastIndexOf('/') + 1);
    LocalResumableSessions sessions = adapter.sessions();

    assertThat(put(sessions, id, "bytes 0-99/" + 3 * PIECE, new byte[100]).status()).isEqualTo(400);
    assertThat(put(sessions, id, "bytes */999", new byte[0]).status()).isEqualTo(400);
    assertThat(put(sessions, "f".repeat(64), "bytes */10", new byte[0]).status()).isEqualTo(404);
    assertThat(put(sessions, "../../etc", "bytes */10", new byte[0]).status()).isEqualTo(404);
  }

  @Test
  void pieceWithoutContentLengthIsCountedAndRolledBackOnMismatch() {
    LocalStorageAdapter adapter = adapter();
    String url =
        adapter
            .createResumableUpload("raw/u/y.webm", "video/webm", 2 * PIECE, Duration.ofDays(1))
            .url();
    String id = url.substring(url.lastIndexOf('/') + 1);
    LocalResumableSessions sessions = adapter.sessions();
    String firstRange = "bytes 0-" + (PIECE - 1) + "/" + 2 * PIECE;

    // Chunked request (length unknown) that is short by one byte: rejected and rolled back.
    var shortPiece =
        sessions.handle(id, firstRange, -1, new ByteArrayInputStream(new byte[PIECE - 1]));
    assertThat(shortPiece.status()).isEqualTo(400);
    assertThat(put(sessions, id, "bytes */" + 2 * PIECE, new byte[0]).committedBytes()).isZero();

    var ok = sessions.handle(id, firstRange, -1, new ByteArrayInputStream(new byte[PIECE]));
    assertThat(ok.committedBytes()).isEqualTo(PIECE);
  }
}

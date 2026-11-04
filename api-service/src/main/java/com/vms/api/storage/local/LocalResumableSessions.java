package com.vms.api.storage.local;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local emulation of the GCS resumable-upload protocol, persisted on disk so sessions survive
 * restarts:
 *
 * <ul>
 *   <li>{@code PUT} with {@code Content-Range: bytes *&#47;TOTAL} queries progress
 *   <li>{@code PUT} with {@code Content-Range: bytes START-END/TOTAL} appends a piece; non-final
 *       pieces must be multiples of 256 KiB
 *   <li>{@code 308} + {@code Range: bytes=0-N} while incomplete, {@code 200} when complete
 * </ul>
 *
 * The session id is a 256-bit random capability, like a GCS session URI.
 */
public class LocalResumableSessions {

  static final String BASE_PATH = "/api/v1/local-storage/sessions/";
  private static final int GRANULARITY = 256 * 1024;
  private static final Pattern RANGE = Pattern.compile("^bytes (?:(\\d+)-(\\d+)|\\*)/(\\d+)$");
  private static final Pattern ID = Pattern.compile("^[0-9a-f]{64}$");

  private final Path dir;
  private final Function<String, Path> resolveKey;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

  public LocalResumableSessions(Path root, Function<String, Path> resolveKey, Clock clock) {
    this.dir = root.toAbsolutePath().normalize().resolve(".sessions");
    this.resolveKey = resolveKey;
    this.clock = clock;
  }

  public String open(String key, String contentType, long total, Duration ttl) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String id = HexFormat.of().formatHex(bytes);
    Properties meta = new Properties();
    meta.setProperty("key", key);
    meta.setProperty("contentType", contentType);
    meta.setProperty("total", Long.toString(total));
    meta.setProperty("expires", Long.toString(clock.instant().plus(ttl).getEpochSecond()));
    try {
      Files.createDirectories(dir);
      try (OutputStream out = Files.newOutputStream(dir.resolve(id + ".meta"))) {
        meta.store(out, null);
      }
      Files.createFile(dir.resolve(id + ".part"));
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
    return BASE_PATH + id;
  }

  /**
   * Applies one request to a session.
   *
   * @return the outcome; {@link Result#notFound()} for unknown or expired sessions
   */
  public Result handle(String id, String contentRange, long contentLength, InputStream body) {
    if (id == null || !ID.matcher(id).matches()) {
      return Result.notFound();
    }
    synchronized (locks.computeIfAbsent(id, k -> new Object())) {
      try {
        Optional<Properties> meta = load(id);
        if (meta.isEmpty()) {
          return Result.notFound();
        }
        long total = Long.parseLong(meta.get().getProperty("total"));
        Path part = dir.resolve(id + ".part");
        long current = Files.size(part);
        Matcher m = contentRange == null ? null : RANGE.matcher(contentRange.trim());
        if (m == null || !m.matches() || Long.parseLong(m.group(3)) != total) {
          return Result.badRequest();
        }
        if (m.group(1) == null) {
          return progress(current, total);
        }
        long start = Long.parseLong(m.group(1));
        long end = Long.parseLong(m.group(2));
        long length = end - start + 1;
        if (start != current) {
          return progress(current, total);
        }
        boolean last = end + 1 == total;
        boolean lengthMismatch = contentLength >= 0 && contentLength != length;
        if (end < start || end >= total || lengthMismatch || (!last && length % GRANULARITY != 0)) {
          return Result.badRequest();
        }
        // Content-Length may be absent (chunked proxying): count what actually arrives and roll the
        // piece back if it doesn't match the declared range.
        long copied;
        try (OutputStream out = Files.newOutputStream(part, StandardOpenOption.APPEND)) {
          copied = body.transferTo(out);
        }
        if (copied != length) {
          try (FileChannel channel = FileChannel.open(part, StandardOpenOption.WRITE)) {
            channel.truncate(current);
          }
          return Result.badRequest();
        }
        if (!last) {
          return progress(end + 1, total);
        }
        finish(id, meta.get(), part);
        return Result.complete();
      } catch (IOException ex) {
        throw new UncheckedIOException(ex);
      }
    }
  }

  private Result progress(long current, long total) {
    return current == total ? Result.complete() : Result.incomplete(current);
  }

  private void finish(String id, Properties meta, Path part) throws IOException {
    Path target = resolveKey.apply(meta.getProperty("key"));
    Files.createDirectories(target.getParent());
    Files.writeString(
        target.resolveSibling(target.getFileName() + LocalStorageAdapter.CONTENT_TYPE_SUFFIX),
        meta.getProperty("contentType"));
    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
    Files.deleteIfExists(dir.resolve(id + ".meta"));
  }

  private Optional<Properties> load(String id) throws IOException {
    Path metaFile = dir.resolve(id + ".meta");
    if (!Files.exists(metaFile)) {
      return Optional.empty();
    }
    Properties meta = new Properties();
    try (InputStream in = Files.newInputStream(metaFile)) {
      meta.load(in);
    }
    if (Instant.ofEpochSecond(Long.parseLong(meta.getProperty("expires")))
        .isBefore(clock.instant())) {
      return Optional.empty();
    }
    return Optional.of(meta);
  }

  /** HTTP-level outcome: 200 complete, 308 incomplete (with committed bytes), 400, 404. */
  public record Result(int status, long committedBytes) {

    static Result complete() {
      return new Result(200, -1);
    }

    static Result incomplete(long committed) {
      return new Result(308, committed);
    }

    static Result badRequest() {
      return new Result(400, -1);
    }

    static Result notFound() {
      return new Result(404, -1);
    }
  }
}

package com.vms.watermark.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Per-job scratch directory, deleted on close (try-with-resources) even when the job fails. */
public final class TempWorkspace implements AutoCloseable {

  private final Path dir;

  private TempWorkspace(Path dir) {
    this.dir = dir;
  }

  public static TempWorkspace create(Path parent, String prefix) {
    try {
      Files.createDirectories(parent);
      return new TempWorkspace(Files.createTempDirectory(parent, prefix + "-"));
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  public Path dir() {
    return dir;
  }

  public Path file(String name) {
    return dir.resolve(name);
  }

  @Override
  public void close() {
    try (Stream<Path> paths = Files.walk(dir)) {
      paths.sorted(Comparator.reverseOrder()).forEach(TempWorkspace::deleteQuietly);
    } catch (IOException ignored) {
      // Best effort: the work dir is ephemeral container storage.
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // Best effort.
    }
  }
}

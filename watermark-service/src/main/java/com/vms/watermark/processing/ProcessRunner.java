package com.vms.watermark.processing;

import com.vms.watermark.exception.RetryableProcessingException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Runs an external binary safely: argument list (never a shell string, so no injection), output to
 * files (no pipe-buffer deadlocks), hard timeout with forced kill.
 */
@Component
public class ProcessRunner {

  private static final int TAIL_BYTES = 2048;

  public Result run(List<String> command, Duration timeout, Path stdout, Path stderr) {
    Process process;
    try {
      process =
          new ProcessBuilder(command)
              .redirectOutput(stdout.toFile())
              .redirectError(stderr.toFile())
              .start();
    } catch (IOException ex) {
      throw new RetryableProcessingException("Could not start " + command.get(0), ex);
    }
    try {
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new RetryableProcessingException(
            command.get(0) + " timed out after " + timeout.toSeconds() + "s");
      }
    } catch (InterruptedException ex) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new RetryableProcessingException("Interrupted while running " + command.get(0), ex);
    }
    return new Result(process.exitValue(), tail(stderr));
  }

  private static String tail(Path file) {
    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
      long length = raf.length();
      long start = Math.max(0, length - TAIL_BYTES);
      byte[] bytes = new byte[(int) (length - start)];
      raf.seek(start);
      raf.readFully(bytes);
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (IOException ex) {
      return "";
    }
  }

  public record Result(int exitCode, String stderrTail) {

    public boolean succeeded() {
      return exitCode == 0;
    }
  }
}

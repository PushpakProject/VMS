package com.vms.watermark.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.vms.watermark.config.WatermarkProperties;
import com.vms.watermark.processing.FfmpegVideoProcessor;
import com.vms.watermark.processing.FfprobeClient;
import com.vms.watermark.processing.MediaInfo;
import com.vms.watermark.processing.ProcessRunner;
import com.vms.watermark.storage.LocalObjectStorageAdapter;
import com.vms.watermark.storage.ObjectStoragePort.StoredObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the real pipeline with real ffmpeg on browser-like WebM clips (VP8 + Opus, 720p, 2.5 Mbps).
 * Skipped automatically when ffmpeg is not installed.
 */
class WatermarkJobFfmpegIntegrationTest {

  private static final Path LOGO = Path.of("assets/logo.png").toAbsolutePath();

  @TempDir Path tmp;

  @BeforeAll
  static void requireFfmpeg() {
    assumeTrue(commandWorks("ffmpeg", "-version") && commandWorks("ffprobe", "-version"));
  }

  @ParameterizedTest(name = "{0}s clip")
  @ValueSource(ints = {60, 120})
  void watermarksBrowserRecording(int seconds) throws Exception {
    Path storageRoot = tmp.resolve("storage");
    UUID videoId = UUID.randomUUID();
    String rawKey = "raw/owner/" + videoId + ".webm";
    String finalKey = "videos/owner/" + videoId + ".mp4";
    Path raw = storageRoot.resolve(rawKey);
    Files.createDirectories(raw.getParent());
    generateClip(raw, seconds);

    WatermarkProperties properties =
        new WatermarkProperties(
            "ffmpeg",
            "ffprobe",
            LOGO.toString(),
            tmp.resolve("work").toString(),
            Duration.ofMinutes(3),
            4,
            120,
            10,
            100_000_000,
            0.15,
            0.85,
            "veryfast",
            23,
            2);
    ProcessRunner runner = new ProcessRunner();
    FfprobeClient ffprobe = new FfprobeClient(runner, properties, JsonMapper.builder().build());
    LocalObjectStorageAdapter storage = new LocalObjectStorageAdapter(storageRoot);
    WatermarkJob job =
        new WatermarkJob(
            storage, ffprobe, new FfmpegVideoProcessor(runner, properties), properties);

    long started = System.nanoTime();
    JobResult result = job.execute(new JobRequest(videoId, 1, rawKey, finalKey, "video/webm"));
    long elapsedMs = (System.nanoTime() - started) / 1_000_000;
    System.out.printf("Watermarked %ds 720p clip in %d ms%n", seconds, elapsedMs);

    StoredObject stored = storage.stat(finalKey).orElseThrow();
    assertThat(stored.contentType()).isEqualTo("video/mp4");
    assertThat(stored.metadata()).containsEntry("watermarked", "true");
    assertThat(storage.stat(rawKey)).isEmpty();
    assertThat(result.durationMs()).isBetween(seconds * 1000 - 1500, seconds * 1000 + 1500);
    assertThat(elapsedMs).isLessThan(60_000);

    Path output = storageRoot.resolve(finalKey);
    MediaInfo info = ffprobe.probe(output, Files.createDirectories(tmp.resolve("probe")));
    assertThat(info.width()).isEqualTo(1280);

    Path reference = tmp.resolve("reference.webm");
    generateClip(reference, 3);
    int[] withLogo = averageColor(output, 1062, 630, 192, 64);
    int[] without = averageColor(reference, 1062, 630, 192, 64);
    int difference =
        Math.abs(withLogo[0] - without[0])
            + Math.abs(withLogo[1] - without[1])
            + Math.abs(withLogo[2] - without[2]);
    assertThat(difference).as("logo changes the bottom-right corner").isGreaterThan(40);
  }

  private static void generateClip(Path target, int seconds) throws Exception {
    run(
        List.of(
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=1280x720:rate=30:duration=" + seconds,
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=440:duration=" + seconds,
            "-c:v",
            "libvpx",
            "-deadline",
            "realtime",
            "-cpu-used",
            "8",
            "-b:v",
            "2500k",
            "-c:a",
            "libopus",
            "-b:a",
            "128k",
            target.toString()));
  }

  /** Average RGB of a region in the frame at 1 s, via ffmpeg crop + scale to 1x1. */
  private static int[] averageColor(Path video, int x, int y, int w, int h) throws Exception {
    Process process =
        new ProcessBuilder(
                "ffmpeg",
                "-hide_banner",
                "-loglevel",
                "error",
                "-ss",
                "1",
                "-i",
                video.toString(),
                "-frames:v",
                "1",
                "-vf",
                "crop=" + w + ":" + h + ":" + x + ":" + y + ",scale=1:1",
                "-f",
                "rawvideo",
                "-pix_fmt",
                "rgb24",
                "-")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    byte[] rgb = process.getInputStream().readAllBytes();
    process.waitFor();
    return new int[] {rgb[0] & 0xff, rgb[1] & 0xff, rgb[2] & 0xff};
  }

  private static void run(List<String> command) throws Exception {
    Process process = new ProcessBuilder(command).inheritIO().start();
    if (process.waitFor() != 0) {
      throw new IOException("Command failed: " + command);
    }
  }

  private static boolean commandWorks(String... command) {
    try {
      Process process =
          new ProcessBuilder(command)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      return process.waitFor() == 0;
    } catch (Exception ex) {
      return false;
    }
  }
}

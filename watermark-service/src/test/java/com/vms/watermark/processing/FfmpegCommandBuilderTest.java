package com.vms.watermark.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class FfmpegCommandBuilderTest {

  private FfmpegCommandBuilder builder(int width) {
    return FfmpegCommandBuilder.create()
        .ffmpeg("/usr/bin/ffmpeg")
        .input(Path.of("/work/in put; rm -rf.webm"))
        .logo(Path.of("/app/assets/logo.png"))
        .output(Path.of("/work/out.mp4"))
        .videoWidth(width);
  }

  @Test
  void buildsArgumentListWithoutShell() {
    List<String> args = builder(1280).build();

    assertThat(args.get(0)).isEqualTo("/usr/bin/ffmpeg");
    assertThat(args).containsSequence("-i", "/work/in put; rm -rf.webm");
    assertThat(args).containsSequence("-map", "[out]", "-map", "0:a?");
    assertThat(args).containsSequence("-c:v", "libx264", "-preset", "veryfast", "-crf", "23");
    assertThat(args).containsSequence("-c:a", "aac");
    assertThat(args).containsSequence("-movflags", "+faststart");
    assertThat(args).contains("-nostdin");
    assertThat(args.get(args.size() - 1)).isEqualTo("/work/out.mp4");
  }

  @Test
  void logoScalesWithVideoAndSitsBottomRight() {
    FfmpegCommandBuilder hd = builder(1280);
    assertThat(hd.logoWidth()).isEqualTo(192);
    assertThat(hd.margin()).isEqualTo(26);
    assertThat(hd.filterGraph())
        .contains("[1:v]scale=192:-2")
        .contains("colorchannelmixer=aa=0.85")
        .contains("overlay=W-w-26:H-h-26")
        .contains("format=yuv420p[out]");

    FfmpegCommandBuilder small = builder(160);
    assertThat(small.logoWidth()).isEqualTo(48);
    assertThat(small.margin()).isEqualTo(12);
  }

  @Test
  void logoWidthIsAlwaysEven() {
    assertThat(builder(1366).logoWidth() % 2).isZero();
  }

  @Test
  void requiresKnownWidth() {
    assertThatThrownBy(() -> builder(0).build()).isInstanceOf(IllegalStateException.class);
  }
}

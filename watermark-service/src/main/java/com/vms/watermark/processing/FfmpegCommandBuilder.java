package com.vms.watermark.processing;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Builder for the watermark command. Produces an argument list (no shell), so file names can never
 * be interpreted as options or commands.
 *
 * <p>The logo is scaled to a fraction of the video width (so it looks the same at any resolution),
 * made semi-transparent and placed bottom-right with a proportional margin. Output is H.264/AAC MP4
 * with the index at the front ({@code +faststart}) for instant playback.
 */
public final class FfmpegCommandBuilder {

  private static final int MIN_LOGO_WIDTH = 48;
  private static final int MIN_MARGIN = 12;
  private static final double MARGIN_RATIO = 0.02;

  private String ffmpeg = "ffmpeg";
  private Path input;
  private Path logo;
  private Path output;
  private int videoWidth;
  private double logoWidthRatio = 0.15;
  private double logoOpacity = 0.85;
  private String preset = "veryfast";
  private int crf = 23;
  private int threads = 2;

  public static FfmpegCommandBuilder create() {
    return new FfmpegCommandBuilder();
  }

  public FfmpegCommandBuilder ffmpeg(String path) {
    this.ffmpeg = path;
    return this;
  }

  public FfmpegCommandBuilder input(Path input) {
    this.input = input;
    return this;
  }

  public FfmpegCommandBuilder logo(Path logo) {
    this.logo = logo;
    return this;
  }

  public FfmpegCommandBuilder output(Path output) {
    this.output = output;
    return this;
  }

  public FfmpegCommandBuilder videoWidth(int width) {
    this.videoWidth = width;
    return this;
  }

  public FfmpegCommandBuilder logoWidthRatio(double ratio) {
    this.logoWidthRatio = ratio;
    return this;
  }

  public FfmpegCommandBuilder logoOpacity(double opacity) {
    this.logoOpacity = opacity;
    return this;
  }

  public FfmpegCommandBuilder preset(String preset) {
    this.preset = preset;
    return this;
  }

  public FfmpegCommandBuilder crf(int crf) {
    this.crf = crf;
    return this;
  }

  public FfmpegCommandBuilder threads(int threads) {
    this.threads = threads;
    return this;
  }

  int logoWidth() {
    int width = (int) Math.round(videoWidth * logoWidthRatio);
    width = Math.max(MIN_LOGO_WIDTH, width);
    return width - (width % 2);
  }

  int margin() {
    return Math.max(MIN_MARGIN, (int) Math.round(videoWidth * MARGIN_RATIO));
  }

  String filterGraph() {
    return String.format(
        Locale.ROOT,
        "[1:v]scale=%d:-2,format=rgba,colorchannelmixer=aa=%.2f[logo];"
            + "[0:v][logo]overlay=W-w-%d:H-h-%d:format=auto,"
            + "scale=trunc(iw/2)*2:trunc(ih/2)*2,format=yuv420p[out]",
        logoWidth(),
        logoOpacity,
        margin(),
        margin());
  }

  public List<String> build() {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(logo, "logo");
    Objects.requireNonNull(output, "output");
    if (videoWidth <= 0) {
      throw new IllegalStateException("videoWidth must be known");
    }
    List<String> args = new ArrayList<>();
    args.add(ffmpeg);
    args.addAll(List.of("-hide_banner", "-nostdin", "-y", "-loglevel", "error"));
    args.addAll(List.of("-i", input.toString(), "-i", logo.toString()));
    args.addAll(List.of("-filter_complex", filterGraph()));
    args.addAll(List.of("-map", "[out]", "-map", "0:a?"));
    args.addAll(List.of("-c:v", "libx264", "-preset", preset, "-crf", Integer.toString(crf)));
    args.addAll(List.of("-c:a", "aac", "-b:a", "128k"));
    args.addAll(List.of("-movflags", "+faststart", "-threads", Integer.toString(threads)));
    args.add(output.toString());
    return List.copyOf(args);
  }
}

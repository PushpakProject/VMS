package com.vms.watermark.processing;

import java.nio.file.Path;

/** Port for the video transformation, so the job pipeline doesn't depend on ffmpeg directly. */
public interface VideoProcessor {

  /** Writes a watermarked MP4 to {@code output}. */
  void watermark(Path input, MediaInfo inputInfo, Path output, Path workDir);
}

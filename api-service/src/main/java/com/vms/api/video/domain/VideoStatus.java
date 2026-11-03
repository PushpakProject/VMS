package com.vms.api.video.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Server-side lifecycle of a video (state machine). Transitions not listed here are rejected, which
 * makes duplicate or out-of-order events harmless.
 *
 * <pre>
 * UPLOADING -> UPLOADED -> PROCESSING -> READY
 *     |            |            |
 *     |            +------------+--> FAILED --(retry)--> UPLOADED
 *     +--> FAILED / ABANDONED
 * </pre>
 */
public enum VideoStatus {
  UPLOADING,
  UPLOADED,
  PROCESSING,
  READY,
  FAILED,
  ABANDONED;

  public Set<VideoStatus> allowedNext() {
    return switch (this) {
      case UPLOADING -> EnumSet.of(UPLOADED, FAILED, ABANDONED);
      case UPLOADED -> EnumSet.of(PROCESSING, READY, FAILED);
      case PROCESSING -> EnumSet.of(READY, FAILED);
      case FAILED -> EnumSet.of(UPLOADED);
      case READY, ABANDONED -> EnumSet.noneOf(VideoStatus.class);
    };
  }

  public boolean canTransitionTo(VideoStatus next) {
    return allowedNext().contains(next);
  }

  public boolean isTerminal() {
    return this == READY || this == FAILED || this == ABANDONED;
  }
}

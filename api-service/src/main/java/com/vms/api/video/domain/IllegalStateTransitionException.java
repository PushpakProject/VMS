package com.vms.api.video.domain;

public class IllegalStateTransitionException extends RuntimeException {

  public IllegalStateTransitionException(VideoStatus from, VideoStatus to) {
    super("Illegal video status transition " + from + " -> " + to);
  }
}

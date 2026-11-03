package com.vms.api.video.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VideoStatusTest {

  @ParameterizedTest(name = "{0} -> {1} allowed")
  @CsvSource({
    "UPLOADING, UPLOADED",
    "UPLOADING, FAILED",
    "UPLOADING, ABANDONED",
    "UPLOADED, PROCESSING",
    "UPLOADED, READY",
    "UPLOADED, FAILED",
    "PROCESSING, READY",
    "PROCESSING, FAILED",
    "FAILED, UPLOADED"
  })
  void allowsDocumentedTransitions(VideoStatus from, VideoStatus to) {
    assertThat(from.canTransitionTo(to)).isTrue();
  }

  @ParameterizedTest(name = "{0} -> {1} rejected")
  @CsvSource({
    "READY, PROCESSING",
    "READY, FAILED",
    "READY, UPLOADED",
    "PROCESSING, UPLOADED",
    "UPLOADING, READY",
    "UPLOADING, PROCESSING",
    "ABANDONED, UPLOADED",
    "FAILED, READY"
  })
  void rejectsEverythingElse(VideoStatus from, VideoStatus to) {
    assertThat(from.canTransitionTo(to)).isFalse();
  }

  @Test
  void terminalStates() {
    assertThat(VideoStatus.READY.isTerminal()).isTrue();
    assertThat(VideoStatus.FAILED.isTerminal()).isTrue();
    assertThat(VideoStatus.ABANDONED.isTerminal()).isTrue();
    assertThat(VideoStatus.PROCESSING.isTerminal()).isFalse();
  }
}

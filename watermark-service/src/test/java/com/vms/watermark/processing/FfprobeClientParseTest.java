package com.vms.watermark.processing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class FfprobeClientParseTest {

  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void browserWebmWithoutDuration() {
    MediaInfo info =
        FfprobeClient.parse(
            json.readTree(
                """
                {"streams":[{"codec_type":"video","width":1280,"height":720},
                            {"codec_type":"audio"}],
                 "format":{"format_name":"matroska,webm"}}
                """));
    assertThat(info.hasVideo()).isTrue();
    assertThat(info.width()).isEqualTo(1280);
    assertThat(info.durationSec()).isNull();
  }

  @Test
  void mp4WithDuration() {
    MediaInfo info =
        FfprobeClient.parse(
            json.readTree(
                """
                {"streams":[{"codec_type":"video","width":640,"height":360}],
                 "format":{"duration":"61.250000"}}
                """));
    assertThat(info.durationSec()).isEqualTo(61.25);
    assertThat(info.durationMs()).isEqualTo(61_250);
  }

  @Test
  void audioOnlyHasNoVideo() {
    MediaInfo info =
        FfprobeClient.parse(
            json.readTree("{\"streams\":[{\"codec_type\":\"audio\"}],\"format\":{}}"));
    assertThat(info.hasVideo()).isFalse();
  }
}

package com.vms.api.common.web;

import com.vms.api.auth.infrastructure.GoogleAuthProperties;
import com.vms.api.common.config.RecordingProperties;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Unauthenticated endpoints: runtime client configuration and a connectivity probe. */
@RestController
@RequestMapping("/api/v1")
public class PublicController {

  private final RecordingProperties recording;
  private final GoogleAuthProperties google;

  public PublicController(RecordingProperties recording, GoogleAuthProperties google) {
    this.recording = recording;
    this.google = google;
  }

  @GetMapping("/config/public")
  public PublicConfig publicConfig() {
    return new PublicConfig(
        recording.maxDurationSec(),
        recording.warnAtSec(),
        recording.maxUploadBytes(),
        recording.allowedContentTypes(),
        recording.videoBitsPerSecond(),
        recording.videoWidth(),
        recording.videoHeight(),
        google.enabled() ? google.clientId() : null);
  }

  /** Lightweight probe used by the browser to tell "Wi-Fi up" from "internet reachable". */
  @GetMapping("/ping")
  public ResponseEntity<Void> ping() {
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  public record PublicConfig(
      int maxDurationSec,
      int warnAtSec,
      long maxUploadBytes,
      List<String> allowedContentTypes,
      int videoBitsPerSecond,
      int videoWidth,
      int videoHeight,
      String googleClientId) {}
}

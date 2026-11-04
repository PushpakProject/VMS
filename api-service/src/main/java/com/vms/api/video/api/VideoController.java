package com.vms.api.video.api;

import com.vms.api.common.error.ApiException;
import com.vms.api.common.error.ErrorCode;
import com.vms.api.common.security.CurrentUser;
import com.vms.api.common.web.PageResponse;
import com.vms.api.storage.StoragePort.SignedUrl;
import com.vms.api.storage.StoragePort.UploadTarget;
import com.vms.api.storage.UploadProperties;
import com.vms.api.video.api.VideoDtos.CompleteUploadRequest;
import com.vms.api.video.api.VideoDtos.InitiateUploadRequest;
import com.vms.api.video.api.VideoDtos.InitiateUploadResponse;
import com.vms.api.video.api.VideoDtos.PlaybackUrlResponse;
import com.vms.api.video.api.VideoDtos.UploadInstruction;
import com.vms.api.video.api.VideoDtos.VideoResponse;
import com.vms.api.video.application.VideoService;
import com.vms.api.video.application.VideoService.InitiateUpload;
import com.vms.api.video.application.VideoService.UploadSession;
import com.vms.api.video.application.VideoService.VideoDetails;
import com.vms.api.video.domain.VideoStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1")
public class VideoController {

  private final VideoService videoService;
  private final UploadProperties uploadProperties;

  public VideoController(VideoService videoService, UploadProperties uploadProperties) {
    this.videoService = videoService;
    this.uploadProperties = uploadProperties;
  }

  @PostMapping("/videos")
  public ResponseEntity<InitiateUploadResponse> initiateUpload(
      CurrentUser user,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody InitiateUploadRequest request) {
    UploadSession session =
        videoService.initiateUpload(
            user,
            parseIdempotencyKey(idempotencyKey),
            new InitiateUpload(
                request.trackingId(),
                request.contentType(),
                request.sizeBytes(),
                request.recordedAt()));
    UploadTarget target = session.upload();
    UploadInstruction instruction =
        target == null
            ? null
            : new UploadInstruction(
                target.protocol().name(),
                target.url(),
                target.method(),
                target.headers(),
                target.expiresAt(),
                target.protocol() == UploadProperties.Protocol.RESUMABLE
                    ? uploadProperties.chunkSizeBytes()
                    : null);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new InitiateUploadResponse(
                session.video().getId(), session.video().getStatus().name(), instruction));
  }

  @PostMapping("/videos/{id}/complete")
  public ResponseEntity<VideoResponse> completeUpload(
      CurrentUser user, @PathVariable UUID id, @Valid @RequestBody CompleteUploadRequest request) {
    var video = videoService.completeUpload(user, id, request.durationMs());
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(VideoResponse.from(video, List.of()));
  }

  @GetMapping("/videos")
  public PageResponse<VideoResponse> list(
      CurrentUser user,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
      @RequestParam(required = false) @Pattern(regexp = "^[A-Za-z0-9_-]{1,64}$") String trackingId,
      @RequestParam(required = false) VideoStatus status) {
    var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    return PageResponse.of(
        videoService.list(user, trackingId, status, pageable),
        v -> VideoResponse.from(v, List.of()));
  }

  @GetMapping("/videos/{id}")
  public VideoResponse get(CurrentUser user, @PathVariable UUID id) {
    VideoDetails details = videoService.get(user, id);
    return VideoResponse.from(details.video(), details.attempts());
  }

  @GetMapping("/videos/{id}/playback-url")
  public PlaybackUrlResponse playbackUrl(CurrentUser user, @PathVariable UUID id) {
    SignedUrl url = videoService.playbackUrl(user, id);
    return new PlaybackUrlResponse(url.url(), url.expiresAt());
  }

  @PostMapping("/videos/{id}/retry")
  public ResponseEntity<VideoResponse> retry(CurrentUser user, @PathVariable UUID id) {
    var video = videoService.retry(user, id, false);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(VideoResponse.from(video, List.of()));
  }

  @PostMapping("/admin/videos/{id}/reprocess")
  public ResponseEntity<VideoResponse> reprocess(CurrentUser user, @PathVariable UUID id) {
    var video = videoService.retry(user, id, true);
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(VideoResponse.from(video, List.of()));
  }

  @DeleteMapping("/videos/{id}")
  public ResponseEntity<Void> delete(CurrentUser user, @PathVariable UUID id) {
    videoService.delete(user, id);
    return ResponseEntity.noContent().build();
  }

  private static UUID parseIdempotencyKey(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException ex) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key must be a UUID.");
    }
  }
}

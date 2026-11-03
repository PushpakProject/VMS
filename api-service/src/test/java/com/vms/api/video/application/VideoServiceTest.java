package com.vms.api.video.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vms.api.common.config.KafkaTopicsProperties;
import com.vms.api.common.config.RecordingProperties;
import com.vms.api.common.error.ApiException;
import com.vms.api.common.error.ErrorCode;
import com.vms.api.common.security.CurrentUser;
import com.vms.api.outbox.OutboxService;
import com.vms.api.storage.StoragePort;
import com.vms.api.storage.StoragePort.ObjectStat;
import com.vms.api.storage.StoragePort.UploadTarget;
import com.vms.api.video.application.VideoService.InitiateUpload;
import com.vms.api.video.domain.FailureCode;
import com.vms.api.video.domain.TrackingId;
import com.vms.api.video.domain.Video;
import com.vms.api.video.domain.VideoStatus;
import com.vms.api.video.events.VideoEvents;
import com.vms.api.video.infrastructure.ProcessingAttemptRepository;
import com.vms.api.video.infrastructure.VideoRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VideoServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");
  private static final long MAX = 100L * 1024 * 1024;

  private final VideoRepository videos = mock(VideoRepository.class);
  private final ProcessingAttemptRepository attempts = mock(ProcessingAttemptRepository.class);
  private final StoragePort storage = mock(StoragePort.class);
  private final OutboxService outbox = mock(OutboxService.class);
  private final CurrentUser owner = new CurrentUser(UUID.randomUUID(), false);
  private final CurrentUser stranger = new CurrentUser(UUID.randomUUID(), false);
  private final CurrentUser admin = new CurrentUser(UUID.randomUUID(), true);
  private VideoService service;

  @BeforeEach
  void setUp() {
    RecordingProperties recording =
        new RecordingProperties(
            120,
            105,
            10,
            MAX,
            List.of("video/webm", "video/mp4"),
            2_500_000,
            1280,
            720,
            Duration.ofMinutes(15),
            Duration.ofMinutes(5));
    KafkaTopicsProperties topics =
        new KafkaTopicsProperties("video.uploaded.v1", "video.watermark.result.v1", 3, (short) 1);
    service =
        new VideoService(
            videos, attempts, storage, outbox, recording, topics, Clock.fixed(NOW, ZoneOffset.UTC));
    when(videos.save(any(Video.class))).thenAnswer(inv -> inv.getArgument(0));
    when(storage.createUploadTarget(anyString(), anyString(), anyLong(), any()))
        .thenReturn(new UploadTarget("https://signed", "PUT", Map.of(), NOW.plusSeconds(900)));
  }

  private Video uploadingVideo() {
    Video video =
        Video.create(
            owner.id(), TrackingId.of("TRK-1"), "video/webm", 1000, UUID.randomUUID(), NOW, NOW);
    when(videos.findById(video.getId())).thenReturn(Optional.of(video));
    return video;
  }

  @Test
  void initiateCreatesVideoAndSignsUploadWithSizeCap() {
    var session =
        service.initiateUpload(
            owner,
            UUID.randomUUID(),
            new InitiateUpload("TRK-1", "video/webm;codecs=vp9,opus", 5_000, NOW));

    assertThat(session.video().getContentType()).isEqualTo("video/webm");
    assertThat(session.upload().url()).isEqualTo("https://signed");
    verify(storage)
        .createUploadTarget(
            eq(session.video().getRawObjectKey()), eq("video/webm"), eq(MAX), any());
  }

  @Test
  void initiateIsIdempotentOnClientUploadId() {
    Video existing = uploadingVideo();
    when(videos.findByOwnerIdAndClientUploadId(owner.id(), existing.getClientUploadId()))
        .thenReturn(Optional.of(existing));

    var session =
        service.initiateUpload(
            owner,
            existing.getClientUploadId(),
            new InitiateUpload("TRK-1", "video/webm", 1000, NOW));

    assertThat(session.video()).isSameAs(existing);
    verify(videos, never()).save(any());
  }

  @Test
  void initiateRejectsUnsupportedTypeAndOversize() {
    assertThatThrownBy(
            () ->
                service.initiateUpload(
                    owner, UUID.randomUUID(), new InitiateUpload("TRK-1", "image/png", 10, NOW)))
        .extracting(ex -> ((ApiException) ex).code())
        .isEqualTo(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    assertThatThrownBy(
            () ->
                service.initiateUpload(
                    owner,
                    UUID.randomUUID(),
                    new InitiateUpload("TRK-1", "video/webm", MAX + 1, NOW)))
        .extracting(ex -> ((ApiException) ex).code())
        .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
  }

  @Test
  void completeWithoutObjectIsRejected() {
    Video video = uploadingVideo();
    when(storage.stat(video.getRawObjectKey())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.completeUpload(owner, video.getId(), 60_000))
        .extracting(ex -> ((ApiException) ex).code())
        .isEqualTo(ErrorCode.UPLOAD_NOT_FOUND);
    assertThat(video.getStatus()).isEqualTo(VideoStatus.UPLOADING);
  }

  @Test
  void completeVerifiesObjectAndWritesOutboxEvent() {
    Video video = uploadingVideo();
    when(storage.stat(video.getRawObjectKey()))
        .thenReturn(Optional.of(new ObjectStat(4_000, "video/webm")));

    Video result = service.completeUpload(owner, video.getId(), 60_000);

    assertThat(result.getStatus()).isEqualTo(VideoStatus.UPLOADED);
    assertThat(result.getSizeBytes()).isEqualTo(4_000);
    ArgumentCaptor<Object> envelope = ArgumentCaptor.forClass(Object.class);
    verify(outbox)
        .append(
            any(UUID.class),
            eq(video.getId()),
            eq(VideoEvents.VIDEO_UPLOADED),
            eq("video.uploaded.v1"),
            envelope.capture());
    var payload =
        (VideoEvents.VideoUploaded) ((VideoEvents.Envelope<?>) envelope.getValue()).payload();
    assertThat(payload.run()).isEqualTo(1);
    assertThat(payload.finalObjectKey()).isEqualTo(video.watermarkedObjectKey());
  }

  @Test
  void completeIsIdempotentOnceUploaded() {
    Video video = uploadingVideo();
    video.markUploaded(4_000, null, NOW);

    service.completeUpload(owner, video.getId(), 60_000);

    verify(storage, never()).stat(anyString());
    verify(outbox, never()).append(any(), any(), any(), any(), any());
  }

  @Test
  void oversizeObjectIsDeletedAndVideoFailed() {
    Video video = uploadingVideo();
    when(storage.stat(video.getRawObjectKey()))
        .thenReturn(Optional.of(new ObjectStat(MAX + 1, "video/webm")));

    Video result = service.completeUpload(owner, video.getId(), 60_000);

    assertThat(result.getStatus()).isEqualTo(VideoStatus.FAILED);
    assertThat(result.getFailureCode()).isEqualTo(FailureCode.TOO_LARGE);
    verify(storage).delete(video.getRawObjectKey());
    verify(outbox, never()).append(any(), any(), any(), any(), any());
  }

  @Test
  void tooLongRecordingIsRejected() {
    Video video = uploadingVideo();
    when(storage.stat(video.getRawObjectKey()))
        .thenReturn(Optional.of(new ObjectStat(4_000, "video/webm")));

    Video result = service.completeUpload(owner, video.getId(), 131_000);

    assertThat(result.getFailureCode()).isEqualTo(FailureCode.TOO_LONG);
  }

  @Test
  void otherUsersVideoIsNotFoundButAdminCanSeeIt() {
    Video video = uploadingVideo();

    assertThatThrownBy(() -> service.get(stranger, video.getId()))
        .extracting(ex -> ((ApiException) ex).code())
        .isEqualTo(ErrorCode.NOT_FOUND);
    assertThat(service.get(admin, video.getId()).video()).isSameAs(video);
  }

  @Test
  void playbackOnlyWhenReady() {
    Video video = uploadingVideo();

    assertThatThrownBy(() -> service.playbackUrl(owner, video.getId()))
        .extracting(ex -> ((ApiException) ex).code())
        .isEqualTo(ErrorCode.NOT_READY);
  }

  @Test
  void ownerCanRetryOnlyRetryableFailures() {
    Video video = uploadingVideo();
    video.markFailed(FailureCode.CORRUPT_INPUT, NOW);

    assertThatThrownBy(() -> service.retry(owner, video.getId(), false))
        .extracting(ex -> ((ApiException) ex).code())
        .isEqualTo(ErrorCode.NOT_RETRYABLE);

    when(storage.stat(video.getRawObjectKey()))
        .thenReturn(Optional.of(new ObjectStat(4_000, "video/webm")));
    Video retried = service.retry(admin, video.getId(), true);
    assertThat(retried.getStatus()).isEqualTo(VideoStatus.UPLOADED);
  }

  @Test
  void retryRequiresRawObject() {
    Video video = uploadingVideo();
    video.markUploaded(4_000, null, NOW);
    video.markFailed(FailureCode.PROCESSING_FAILED, NOW);
    when(storage.stat(video.getRawObjectKey())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.retry(owner, video.getId(), false))
        .extracting(ex -> ((ApiException) ex).code())
        .isEqualTo(ErrorCode.RAW_NOT_AVAILABLE);
  }
}

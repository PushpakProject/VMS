package com.vms.api.video.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One watermarking attempt, written by the API from the watermark service's result events (the
 * watermark service owns no database). Unique per (video, run, attempt), so replays are upserts.
 */
@Entity
@Table(name = "video_processing_attempts")
public class ProcessingAttempt {

  public enum Status {
    STARTED,
    RETRYING,
    FAILED,
    COMPLETED
  }

  @Id private UUID id;

  @Column(name = "video_id", nullable = false)
  private UUID videoId;

  @Column(name = "run_number", nullable = false)
  private int runNumber;

  @Column(name = "attempt_number", nullable = false)
  private int attemptNumber;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Status status;

  @Column(name = "error_code")
  private String errorCode;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

  protected ProcessingAttempt() {}

  public ProcessingAttempt(UUID videoId, int runNumber, int attemptNumber, Instant startedAt) {
    this.id = UUID.randomUUID();
    this.videoId = videoId;
    this.runNumber = runNumber;
    this.attemptNumber = attemptNumber;
    this.status = Status.STARTED;
    this.startedAt = startedAt;
  }

  public void record(Status newStatus, String code, String message, Instant at) {
    this.status = newStatus;
    this.errorCode = code;
    this.errorMessage = message;
    if (newStatus != Status.STARTED) {
      this.finishedAt = at;
    }
  }

  public int getRunNumber() {
    return runNumber;
  }

  public int getAttemptNumber() {
    return attemptNumber;
  }

  public Status getStatus() {
    return status;
  }

  public String getErrorCode() {
    return errorCode;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getFinishedAt() {
    return finishedAt;
  }
}

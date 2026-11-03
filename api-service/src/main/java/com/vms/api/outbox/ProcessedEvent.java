package com.vms.api.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Idempotent-consumer record: an event id is processed at most once. */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

  @Id
  @Column(name = "event_id")
  private UUID eventId;

  @Column(nullable = false)
  private String consumer;

  @Column(name = "processed_at", nullable = false)
  private Instant processedAt;

  protected ProcessedEvent() {}

  public ProcessedEvent(UUID eventId, String consumer, Instant processedAt) {
    this.eventId = eventId;
    this.consumer = consumer;
    this.processedAt = processedAt;
  }
}

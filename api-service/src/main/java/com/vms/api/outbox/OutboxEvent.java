package com.vms.api.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/**
 * Transactional outbox row. Written in the same transaction as the business change and published to
 * Kafka afterwards by {@link OutboxRelay}, so a Kafka outage can never lose an event.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

  private static final int MAX_ERROR_LENGTH = 500;

  @Id private UUID id;

  @Column(name = "aggregate_id", nullable = false)
  private UUID aggregateId;

  @Column(name = "event_type", nullable = false)
  private String eventType;

  @Column(nullable = false)
  private String topic;

  @Column(name = "message_key", nullable = false)
  private String messageKey;

  @ColumnTransformer(write = "?::jsonb")
  @Column(nullable = false, columnDefinition = "jsonb")
  private String payload;

  @Column(name = "correlation_id")
  private String correlationId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "published_at")
  private Instant publishedAt;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "last_error")
  private String lastError;

  protected OutboxEvent() {}

  public OutboxEvent(
      UUID id,
      UUID aggregateId,
      String eventType,
      String topic,
      String messageKey,
      String payload,
      String correlationId,
      Instant createdAt) {
    this.id = id;
    this.aggregateId = aggregateId;
    this.eventType = eventType;
    this.topic = topic;
    this.messageKey = messageKey;
    this.payload = payload;
    this.correlationId = correlationId;
    this.createdAt = createdAt;
  }

  public void markPublished(Instant now) {
    this.publishedAt = now;
    this.attempts++;
    this.lastError = null;
  }

  public void markFailed(String error) {
    this.attempts++;
    this.lastError =
        error == null ? null : error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
  }

  public UUID getId() {
    return id;
  }

  public String getEventType() {
    return eventType;
  }

  public String getTopic() {
    return topic;
  }

  public String getMessageKey() {
    return messageKey;
  }

  public String getPayload() {
    return payload;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public int getAttempts() {
    return attempts;
  }
}

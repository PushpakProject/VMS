package com.vms.api.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes pending outbox rows to Kafka (at-least-once). A row is marked published only after the
 * broker acknowledged it; on failure it stays pending and is retried on the next tick.
 */
@Component
public class OutboxRelay {

  public static final String CORRELATION_HEADER = "x-correlation-id";
  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private static final int BATCH_SIZE = 50;
  private static final long SEND_TIMEOUT_SECONDS = 10;

  private final OutboxRepository repository;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public OutboxRelay(
      OutboxRepository repository,
      KafkaTemplate<String, String> kafkaTemplate,
      TransactionTemplate transactions,
      Clock clock) {
    this.repository = repository;
    this.kafkaTemplate = kafkaTemplate;
    this.transactions = transactions;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${vms.outbox.poll-interval:1000}")
  public void publishPending() {
    Integer published;
    do {
      published = transactions.execute(status -> publishBatch());
    } while (published != null && published == BATCH_SIZE);
  }

  private int publishBatch() {
    List<OutboxEvent> batch = repository.lockNextBatch(BATCH_SIZE);
    int published = 0;
    for (OutboxEvent event : batch) {
      try {
        ProducerRecord<String, String> record =
            new ProducerRecord<>(event.getTopic(), event.getMessageKey(), event.getPayload());
        if (event.getCorrelationId() != null) {
          record
              .headers()
              .add(CORRELATION_HEADER, event.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        }
        kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        event.markPublished(clock.instant());
        published++;
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        event.markFailed("interrupted");
        break;
      } catch (Exception ex) {
        event.markFailed(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        log.warn(
            "Outbox publish failed for event {} (attempt {})", event.getId(), event.getAttempts());
        break;
      }
    }
    return published;
  }
}

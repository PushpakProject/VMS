package com.vms.watermark.messaging;

import com.vms.watermark.config.KafkaTopicsProperties;
import com.vms.watermark.messaging.WatermarkEvents.Envelope;
import com.vms.watermark.messaging.WatermarkEvents.WatermarkResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes result events and waits for the broker ack, so the source message is only committed
 * after the result is durable. Keyed by videoId to keep per-video ordering.
 */
@Component
public class WatermarkResultPublisher {

  public static final String CORRELATION_HEADER = "x-correlation-id";
  private static final long SEND_TIMEOUT_SECONDS = 10;

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final KafkaTopicsProperties topics;
  private final JsonMapper jsonMapper;
  private final Clock clock;

  public WatermarkResultPublisher(
      KafkaTemplate<String, String> kafkaTemplate,
      KafkaTopicsProperties topics,
      JsonMapper jsonMapper,
      Clock clock) {
    this.kafkaTemplate = kafkaTemplate;
    this.topics = topics;
    this.jsonMapper = jsonMapper;
    this.clock = clock;
  }

  public void publish(WatermarkResult result, String correlationId) {
    Envelope<WatermarkResult> envelope =
        new Envelope<>(
            UUID.randomUUID(),
            WatermarkEvents.WATERMARK_RESULT,
            WatermarkEvents.VERSION,
            clock.instant(),
            correlationId,
            result);
    ProducerRecord<String, String> record =
        new ProducerRecord<>(
            topics.watermarkResultTopic(),
            result.videoId().toString(),
            jsonMapper.writeValueAsString(envelope));
    if (correlationId != null) {
      record.headers().add(CORRELATION_HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
    }
    try {
      kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while publishing result", ex);
    } catch (ExecutionException | TimeoutException ex) {
      throw new IllegalStateException("Could not publish result event", ex);
    }
  }
}

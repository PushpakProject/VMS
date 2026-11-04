package com.vms.watermark.messaging;

import com.vms.watermark.application.JobRequest;
import com.vms.watermark.application.JobResult;
import com.vms.watermark.application.WatermarkJob;
import com.vms.watermark.config.WatermarkProperties;
import com.vms.watermark.exception.FailureCode;
import com.vms.watermark.exception.NonRetryableProcessingException;
import com.vms.watermark.exception.ProcessingException;
import com.vms.watermark.messaging.WatermarkEvents.Envelope;
import com.vms.watermark.messaging.WatermarkEvents.Outcome;
import com.vms.watermark.messaging.WatermarkEvents.VideoUploaded;
import com.vms.watermark.messaging.WatermarkEvents.WatermarkResult;
import com.vms.watermark.observability.WatermarkMetrics;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.RetryTopicHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumes {@code video.uploaded.v1}. Non-blocking retries: a failed message moves to a retry topic
 * (10 s, 60 s, 300 s) so the main partition keeps flowing; after the last attempt it lands on the
 * DLT. Bad input is not retried at all.
 *
 * <p>Every attempt reports STARTED and then COMPLETED / RETRYING / FAILED, which the API turns into
 * the video status and attempt history.
 */
@Component
public class VideoUploadedListener {

  private static final Logger log = LoggerFactory.getLogger(VideoUploadedListener.class);
  private static final TypeReference<Envelope<VideoUploaded>> TYPE = new TypeReference<>() {};
  private static final String CORRELATION_MDC = "correlationId";

  private final WatermarkJob job;
  private final WatermarkResultPublisher publisher;
  private final WatermarkProperties properties;
  private final JsonMapper jsonMapper;
  private final WatermarkMetrics metrics;

  public VideoUploadedListener(
      WatermarkJob job,
      WatermarkResultPublisher publisher,
      WatermarkProperties properties,
      JsonMapper jsonMapper,
      WatermarkMetrics metrics) {
    this.job = job;
    this.publisher = publisher;
    this.properties = properties;
    this.jsonMapper = jsonMapper;
    this.metrics = metrics;
  }

  @RetryableTopic(
      attempts = "${vms.watermark.max-attempts}",
      backOff = @BackOff(delay = 10_000, multiplier = 6.0, maxDelay = 300_000),
      exclude = NonRetryableProcessingException.class,
      traversingCauses = "true",
      dltStrategy = DltStrategy.FAIL_ON_ERROR,
      autoCreateTopics = "true",
      numPartitions = "${vms.kafka.partitions:3}",
      replicationFactor = "${vms.kafka.replication-factor:1}")
  @KafkaListener(topics = "${vms.kafka.video-uploaded-topic}", groupId = "watermark-service")
  public void onVideoUploaded(ConsumerRecord<String, String> record) {
    Envelope<VideoUploaded> event = parse(record);
    VideoUploaded payload = event.payload();
    int attempt = attemptOf(record);
    String correlationId = correlationOf(record, event);
    MDC.put(CORRELATION_MDC, correlationId == null ? "" : correlationId);
    try {
      publisher.publish(result(payload, attempt, Outcome.STARTED, null, null), correlationId);
      long started = System.nanoTime();
      JobResult done =
          job.execute(
              new JobRequest(
                  payload.videoId(),
                  payload.run(),
                  payload.rawObjectKey(),
                  payload.finalObjectKey(),
                  payload.contentType()));
      metrics.completed(Duration.ofNanos(System.nanoTime() - started), done.alreadyDone());
      publisher.publish(
          new WatermarkResult(
              payload.videoId(),
              payload.run(),
              attempt,
              Outcome.COMPLETED,
              done.finalObjectKey(),
              done.finalSizeBytes(),
              done.durationMs(),
              null,
              null),
          correlationId);
    } catch (NonRetryableProcessingException ex) {
      log.warn("Video {} failed permanently: {}", payload.videoId(), ex.code());
      metrics.failed(ex.code().name());
      publisher.publish(result(payload, attempt, Outcome.FAILED, ex.code(), ex), correlationId);
      throw ex;
    } catch (RuntimeException ex) {
      FailureCode code =
          ex instanceof ProcessingException p ? p.code() : FailureCode.PROCESSING_FAILED;
      boolean lastAttempt = attempt >= properties.maxAttempts();
      log.warn(
          "Video {} attempt {}/{} failed: {}",
          payload.videoId(),
          attempt,
          properties.maxAttempts(),
          ex.getMessage());
      if (lastAttempt) {
        metrics.failed(code.name());
      } else {
        metrics.retrying(code.name());
      }
      publisher.publish(
          result(payload, attempt, lastAttempt ? Outcome.FAILED : Outcome.RETRYING, code, ex),
          correlationId);
      throw ex;
    } finally {
      MDC.remove(CORRELATION_MDC);
    }
  }

  /** Parked messages stay on the DLT for investigation; the FAILED result was already published. */
  @DltHandler
  public void onDeadLetter(ConsumerRecord<String, String> record) {
    metrics.deadLettered();
    log.error(
        "Message parked on {} (partition {}, offset {}) for key {}",
        record.topic(),
        record.partition(),
        record.offset(),
        record.key());
  }

  private Envelope<VideoUploaded> parse(ConsumerRecord<String, String> record) {
    try {
      Envelope<VideoUploaded> event = jsonMapper.readValue(record.value(), TYPE);
      if (event.payload() == null
          || event.payload().videoId() == null
          || event.payload().rawObjectKey() == null
          || event.payload().finalObjectKey() == null) {
        throw new NonRetryableProcessingException(FailureCode.PROCESSING_FAILED, "Invalid event");
      }
      return event;
    } catch (JacksonException ex) {
      throw new NonRetryableProcessingException(FailureCode.PROCESSING_FAILED, "Unreadable event");
    }
  }

  /** 1 on the main topic; the retry-topic header carries the attempt number afterwards. */
  static int attemptOf(ConsumerRecord<String, String> record) {
    Header header = record.headers().lastHeader(RetryTopicHeaders.DEFAULT_HEADER_ATTEMPTS);
    if (header == null || header.value() == null || header.value().length == 0) {
      return 1;
    }
    return new BigInteger(header.value()).intValue();
  }

  private static String correlationOf(ConsumerRecord<String, String> record, Envelope<?> event) {
    Header header = record.headers().lastHeader(WatermarkResultPublisher.CORRELATION_HEADER);
    if (header != null) {
      return new String(header.value(), StandardCharsets.UTF_8);
    }
    return event.correlationId();
  }

  private static WatermarkResult result(
      VideoUploaded payload, int attempt, Outcome outcome, FailureCode code, Exception cause) {
    return new WatermarkResult(
        payload.videoId(),
        payload.run(),
        attempt,
        outcome,
        null,
        null,
        null,
        code == null ? null : code.name(),
        cause == null ? null : cause.getClass().getSimpleName());
  }
}

package com.vms.api.video.infrastructure;

import com.vms.api.common.web.RequestIdFilter;
import com.vms.api.outbox.OutboxRelay;
import com.vms.api.video.application.WatermarkResultHandler;
import com.vms.api.video.events.VideoEvents.Envelope;
import com.vms.api.video.events.VideoEvents.WatermarkResult;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Kafka adapter: parses result events and delegates to the application handler. */
@Component
public class WatermarkResultListener {

  private static final TypeReference<Envelope<WatermarkResult>> TYPE = new TypeReference<>() {};

  private final WatermarkResultHandler handler;
  private final JsonMapper jsonMapper;

  public WatermarkResultListener(WatermarkResultHandler handler, JsonMapper jsonMapper) {
    this.handler = handler;
    this.jsonMapper = jsonMapper;
  }

  @KafkaListener(topics = "${vms.kafka.watermark-result-topic}", groupId = "api-service")
  public void onResult(ConsumerRecord<String, String> record) {
    Header correlation = record.headers().lastHeader(OutboxRelay.CORRELATION_HEADER);
    if (correlation != null) {
      MDC.put(RequestIdFilter.MDC_KEY, new String(correlation.value(), StandardCharsets.UTF_8));
    }
    try {
      Envelope<WatermarkResult> event = jsonMapper.readValue(record.value(), TYPE);
      if (event.eventId() == null || event.payload() == null || event.payload().videoId() == null) {
        throw new IllegalArgumentException("Result event is missing required fields");
      }
      handler.handle(event);
    } finally {
      MDC.remove(RequestIdFilter.MDC_KEY);
    }
  }
}

package com.vms.api.common.observability;

import com.vms.api.outbox.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Outbox backlog gauge: a growing value means Kafka is unreachable or the relay is stuck. */
@Configuration
public class OutboxMetricsConfig {

  @Bean
  Gauge outboxPendingGauge(MeterRegistry registry, OutboxRepository outbox) {
    return Gauge.builder("vms.outbox.pending", outbox, OutboxRepository::countPending)
        .description("Outbox events not yet published to Kafka")
        .register(registry);
  }
}

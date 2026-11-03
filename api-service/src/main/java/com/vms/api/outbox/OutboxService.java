package com.vms.api.outbox;

import com.vms.api.common.web.RequestIdFilter;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class OutboxService {

  private final OutboxRepository repository;
  private final JsonMapper jsonMapper;
  private final Clock clock;

  public OutboxService(OutboxRepository repository, JsonMapper jsonMapper, Clock clock) {
    this.repository = repository;
    this.jsonMapper = jsonMapper;
    this.clock = clock;
  }

  /**
   * Appends an event inside the caller's transaction (MANDATORY: there is no way to call this
   * without one, which is exactly the outbox guarantee).
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void append(
      UUID eventId, UUID aggregateId, String eventType, String topic, Object envelope) {
    repository.save(
        new OutboxEvent(
            eventId,
            aggregateId,
            eventType,
            topic,
            aggregateId.toString(),
            jsonMapper.writeValueAsString(envelope),
            MDC.get(RequestIdFilter.MDC_KEY),
            clock.instant()));
  }
}

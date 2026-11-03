package com.vms.api.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

  /**
   * Oldest unpublished events, row-locked. {@code SKIP LOCKED} lets several API instances relay in
   * parallel without publishing the same row twice.
   */
  @Query(
      value =
          "select * from outbox_events where published_at is null "
              + "order by created_at limit :limit for update skip locked",
      nativeQuery = true)
  List<OutboxEvent> lockNextBatch(@Param("limit") int limit);

  @Query("select count(e) from OutboxEvent e where e.publishedAt is null")
  long countPending();
}

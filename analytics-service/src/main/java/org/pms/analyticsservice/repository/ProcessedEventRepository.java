package org.pms.analyticsservice.repository;

import java.util.UUID;
import org.pms.analyticsservice.model.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

  /**
   * Records the event as processed unless it already is. Returns 1 when this call recorded it and 0
   * for a duplicate; the primary key decides, so concurrent deliveries cannot both get 1. Must run
   * in the caller's transaction.
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO processed_events (event_id, event_type, processed_at)"
              + " VALUES (:eventId, :eventType, now())"
              + " ON CONFLICT (event_id) DO NOTHING",
      nativeQuery = true)
  int insertIfAbsent(UUID eventId, String eventType);
}

package org.pms.notificationservice.repository;

import java.time.Instant;
import java.util.UUID;
import org.pms.notificationservice.model.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

  /**
   * Records the event as processed unless it already is. Returns 1 when this call inserted the row
   * and 0 when the event was already recorded. A concurrent insert of the same ID waits for the
   * other transaction and then returns 0, so exactly one delivery wins.
   */
  @Modifying
  @Query(
      value =
          "INSERT INTO processed_events (event_id, event_type, processed_at)"
              + " VALUES (:eventId, :eventType, :processedAt)"
              + " ON CONFLICT (event_id) DO NOTHING",
      nativeQuery = true)
  int insertIfAbsent(
      @Param("eventId") UUID eventId,
      @Param("eventType") String eventType,
      @Param("processedAt") Instant processedAt);
}

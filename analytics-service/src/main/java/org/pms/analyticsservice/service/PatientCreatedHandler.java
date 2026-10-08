package org.pms.analyticsservice.service;

import org.pms.analyticsservice.event.PatientCreatedEvent;
import org.pms.analyticsservice.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a {@code patient.created} event at most once. Kafka delivers at least once, so the event
 * ID is recorded in the same local transaction as the effect: both commit or neither does, and a
 * redelivery of a committed event finds the ID and changes nothing.
 */
@Service
public class PatientCreatedHandler {

  private static final Logger log = LoggerFactory.getLogger(PatientCreatedHandler.class);

  private final ProcessedEventRepository processedEvents;
  private final DailyRegistrations dailyRegistrations;

  public PatientCreatedHandler(
      ProcessedEventRepository processedEvents, DailyRegistrations dailyRegistrations) {
    this.processedEvents = processedEvents;
    this.dailyRegistrations = dailyRegistrations;
  }

  /** Returns {@code true} when the effect was applied, {@code false} for a duplicate. */
  @Transactional
  public boolean handle(PatientCreatedEvent event) {
    if (processedEvents.insertIfAbsent(event.eventId(), PatientCreatedEvent.TYPE) == 0) {
      log.debug("Skipping already processed event eventId={}", event.eventId());
      return false;
    }
    dailyRegistrations.record(event);
    return true;
  }
}

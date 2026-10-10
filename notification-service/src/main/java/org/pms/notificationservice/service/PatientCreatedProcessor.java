package org.pms.notificationservice.service;

import java.time.Clock;
import org.pms.notificationservice.messaging.PatientCreatedEvent;
import org.pms.notificationservice.repository.ProcessedEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a patient.created event at most once per event ID. Kafka delivers at least once, so the
 * same event can arrive again (redelivery after a crash or rebalance, or a producer retry); the
 * processed-event marker and the effect commit in one local transaction, so either both exist or
 * neither does and the event is retried.
 */
@Service
public class PatientCreatedProcessor {

  private final ProcessedEventRepository processedEvents;
  private final WelcomeNotificationRecorder welcomeNotifications;
  private final Clock clock;

  public PatientCreatedProcessor(
      ProcessedEventRepository processedEvents,
      WelcomeNotificationRecorder welcomeNotifications,
      Clock clock) {
    this.processedEvents = processedEvents;
    this.welcomeNotifications = welcomeNotifications;
    this.clock = clock;
  }

  /**
   * Returns {@code true} when this call applied the event, {@code false} when it had already been
   * applied (a duplicate delivery, skipped without a second effect).
   */
  @Transactional
  public boolean process(PatientCreatedEvent event) {
    int inserted =
        processedEvents.insertIfAbsent(event.eventId(), PatientCreatedEvent.TYPE, clock.instant());
    if (inserted == 0) {
      return false;
    }
    welcomeNotifications.record(event);
    return true;
  }
}

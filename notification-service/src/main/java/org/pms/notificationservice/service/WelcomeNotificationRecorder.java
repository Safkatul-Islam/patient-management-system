package org.pms.notificationservice.service;

import java.time.Clock;
import org.pms.notificationservice.messaging.PatientCreatedEvent;
import org.pms.notificationservice.model.Notification;
import org.pms.notificationservice.repository.NotificationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The business effect of a patient.created event: one WELCOME notification, recorded rather than
 * delivered. Delivery would need the patient's contact details, which the event deliberately does
 * not carry (no personal data in events); a real sender would look them up through an owned API.
 */
@Component
public class WelcomeNotificationRecorder {

  private final NotificationRepository notifications;
  private final Clock clock;

  public WelcomeNotificationRecorder(NotificationRepository notifications, Clock clock) {
    this.notifications = notifications;
    this.clock = clock;
  }

  /**
   * Runs only inside the caller's transaction, together with the processed-event marker; without
   * one it fails instead of recording a notification that deduplication never saw.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void record(PatientCreatedEvent event) {
    notifications.save(Notification.welcome(event.eventId(), event.patientId(), clock.instant()));
  }
}

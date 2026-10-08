package org.pms.notificationservice.service;

import java.time.Clock;
import org.pms.notificationservice.messaging.PatientCreatedEvent;
import org.pms.notificationservice.model.Notification;
import org.pms.notificationservice.repository.NotificationRepository;
import org.springframework.stereotype.Component;

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

  /** Must run inside the caller's transaction, together with the processed-event marker. */
  public void record(PatientCreatedEvent event) {
    notifications.save(Notification.welcome(event.eventId(), event.patientId(), clock.instant()));
  }
}

package org.pms.notificationservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A notification this service has recorded for a patient. It is recorded, not sent: delivering it
 * would need contact details, which the patient.created event deliberately does not carry.
 */
@Entity
@Table(name = "notifications")
public class Notification {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "event_id", nullable = false, unique = true)
  private UUID eventId;

  @Column(name = "patient_id", nullable = false)
  private UUID patientId;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 32)
  private NotificationKind kind;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private NotificationStatus status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected Notification() {}

  private Notification(
      UUID eventId,
      UUID patientId,
      NotificationKind kind,
      NotificationStatus status,
      Instant createdAt) {
    this.eventId = eventId;
    this.patientId = patientId;
    this.kind = kind;
    this.status = status;
    this.createdAt = createdAt;
  }

  /** The welcome notification for a newly created patient, triggered by the given event. */
  public static Notification welcome(UUID eventId, UUID patientId, Instant createdAt) {
    return new Notification(
        eventId, patientId, NotificationKind.WELCOME, NotificationStatus.RECORDED, createdAt);
  }

  public UUID getId() {
    return id;
  }

  public UUID getEventId() {
    return eventId;
  }

  public UUID getPatientId() {
    return patientId;
  }

  public NotificationKind getKind() {
    return kind;
  }

  public NotificationStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}

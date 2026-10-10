package org.pms.notificationservice.repository;

import java.util.UUID;
import org.pms.notificationservice.model.Notification;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write-only access to recorded notifications. Deliberately not a {@code JpaRepository}: inserting
 * through the deduplicating processor is the only way a notification is meant to come into being.
 */
public interface NotificationRepository extends Repository<Notification, UUID> {

  /** Only inside the transaction that also writes the processed-event marker. */
  @Transactional(propagation = Propagation.MANDATORY)
  Notification save(Notification notification);
}

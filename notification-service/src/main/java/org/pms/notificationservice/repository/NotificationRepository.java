package org.pms.notificationservice.repository;

import java.util.UUID;
import org.pms.notificationservice.model.Notification;
import org.springframework.data.repository.Repository;

/**
 * Write-only access to recorded notifications. Deliberately not a {@code JpaRepository}: inserting
 * through the deduplicating processor is the only way a notification is meant to come into being.
 */
public interface NotificationRepository extends Repository<Notification, UUID> {

  Notification save(Notification notification);
}

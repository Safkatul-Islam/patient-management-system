package org.pms.notificationservice.repository;

import java.util.UUID;
import org.pms.notificationservice.model.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {}

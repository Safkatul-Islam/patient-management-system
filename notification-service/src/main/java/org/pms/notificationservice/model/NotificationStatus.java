package org.pms.notificationservice.model;

/**
 * Where a notification is in its lifecycle. Mirrors the {@code notifications_status_check}
 * constraint; only {@code RECORDED} exists until real delivery is introduced.
 */
public enum NotificationStatus {
  RECORDED
}

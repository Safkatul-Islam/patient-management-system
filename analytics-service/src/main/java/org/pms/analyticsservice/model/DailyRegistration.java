package org.pms.analyticsservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * Patient registrations counted for one UTC calendar day. Rows are only ever changed by {@link
 * org.pms.analyticsservice.repository.DailyRegistrationRepository#increment}.
 */
@Entity
@Table(name = "daily_registrations")
public class DailyRegistration {

  @Id
  @Column(name = "day")
  private LocalDate day;

  @Column(name = "registrations", nullable = false)
  private long registrations;

  protected DailyRegistration() {}

  public LocalDate getDay() {
    return day;
  }

  public long getRegistrations() {
    return registrations;
  }
}

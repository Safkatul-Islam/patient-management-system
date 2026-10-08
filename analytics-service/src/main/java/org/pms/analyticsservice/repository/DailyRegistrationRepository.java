package org.pms.analyticsservice.repository;

import java.time.LocalDate;
import org.pms.analyticsservice.model.DailyRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DailyRegistrationRepository extends JpaRepository<DailyRegistration, LocalDate> {

  /** Adds one registration to {@code day}, creating its row on the first one. Atomic upsert. */
  @Modifying
  @Query(
      value =
          "INSERT INTO daily_registrations (day, registrations) VALUES (:day, 1)"
              + " ON CONFLICT (day)"
              + " DO UPDATE SET registrations = daily_registrations.registrations + 1",
      nativeQuery = true)
  int increment(LocalDate day);
}

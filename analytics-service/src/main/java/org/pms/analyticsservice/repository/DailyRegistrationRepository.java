package org.pms.analyticsservice.repository;

import java.time.LocalDate;
import org.pms.analyticsservice.model.DailyRegistration;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface DailyRegistrationRepository extends Repository<DailyRegistration, LocalDate> {

  /**
   * Adds one registration to {@code day}, creating its row on the first one. Atomic upsert.
   * MANDATORY: the count may only change inside the transaction that records the event as
   * processed, so it can never be applied without its idempotency row.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying
  @Query(
      value =
          "INSERT INTO daily_registrations (day, registrations) VALUES (:day, 1)"
              + " ON CONFLICT (day)"
              + " DO UPDATE SET registrations = daily_registrations.registrations + 1",
      nativeQuery = true)
  int increment(LocalDate day);
}

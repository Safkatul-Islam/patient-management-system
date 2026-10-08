package org.pms.analyticsservice.service;

import org.pms.analyticsservice.event.PatientCreatedEvent;
import org.pms.analyticsservice.repository.DailyRegistrationRepository;
import org.springframework.stereotype.Component;

/** The business effect of a {@code patient.created} event: one more registration that day. */
@Component
public class DailyRegistrations {

  private final DailyRegistrationRepository repository;

  public DailyRegistrations(DailyRegistrationRepository repository) {
    this.repository = repository;
  }

  /** Counts the registration; runs in the caller's transaction. */
  public void record(PatientCreatedEvent event) {
    repository.increment(event.registrationDay());
  }
}

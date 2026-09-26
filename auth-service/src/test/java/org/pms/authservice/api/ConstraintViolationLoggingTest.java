package org.pms.authservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pms.authservice.model.Role;
import org.pms.authservice.model.User;
import org.pms.authservice.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * A unique-constraint violation must not put account data into logs. PgJDBC copies the server's
 * DETAIL ({@code Key (email)=(...)}) into the exception message unless told not to, and Hibernate
 * logs that message. These tests reach the real database constraint, bypassing the service's
 * pre-check, and inspect everything written to the console.
 */
@ExtendWith(OutputCaptureExtension.class)
class ConstraintViolationLoggingTest extends AbstractAuthApiTest {

  private static final int CONCURRENT_CREATES = 6;

  @Autowired private UserRepository userRepository;

  @Test
  @DisplayName("Duplicate email at the DB constraint: still mapped by name, email not logged")
  void duplicateEmailConstraintDoesNotLogEmail(CapturedOutput output) {
    String email = uniqueEmail();
    userRepository.saveAndFlush(staff(email));

    Throwable failure = catchThrowable(() -> userRepository.saveAndFlush(staff(email)));

    assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
    assertThat(constraintName(failure)).isEqualTo("users_email_key");
    assertThat(fullMessage(failure)).doesNotContain(email).doesNotContain("Key (");
    // Positive control: Hibernate did log the violation, so the capture saw it.
    assertThat(output.getAll()).contains("users_email_key");
    assertThat(output.getAll()).doesNotContain(email).doesNotContain("Key (");
  }

  @Test
  @DisplayName("Duplicate patient link at the DB constraint: mapped by name, id not logged")
  void duplicatePatientConstraintDoesNotLogPatientId(CapturedOutput output) {
    UUID patientId = UUID.randomUUID();
    userRepository.saveAndFlush(patient(patientId));

    Throwable failure = catchThrowable(() -> userRepository.saveAndFlush(patient(patientId)));

    assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
    assertThat(constraintName(failure)).isEqualTo("users_patient_id_key");
    assertThat(fullMessage(failure)).doesNotContain(patientId.toString()).doesNotContain("Key (");
    assertThat(output.getAll()).contains("users_patient_id_key");
    assertThat(output.getAll()).doesNotContain(patientId.toString()).doesNotContain("Key (");
  }

  @Test
  @DisplayName("Concurrent creates with one email: one 201, the rest 409, email never logged")
  void concurrentDuplicateCreatesAreConflictsWithoutLeaks(CapturedOutput output) throws Exception {
    String email = uniqueEmail();
    String adminToken = adminAccessToken();
    CountDownLatch start = new CountDownLatch(1);
    Callable<Integer> create =
        () -> {
          start.await();
          return postJson(
                  "/auth/admin/users", staffJson(email, randomPassword(), "NURSE"), adminToken)
              .andReturn()
              .getResponse()
              .getStatus();
        };

    ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CREATES);
    List<Integer> statuses = new ArrayList<>();
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_CREATES; i++) {
        results.add(pool.submit(create));
      }
      start.countDown();
      for (Future<Integer> result : results) {
        statuses.add(result.get(30, TimeUnit.SECONDS));
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(statuses).containsOnly(201, 409);
    assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
    assertThat(output.getAll()).doesNotContain(email).doesNotContain("Key (");
  }

  private static User staff(String email) {
    return new User(email, "not-a-real-hash", Role.DOCTOR, null, Instant.now());
  }

  private static User patient(UUID patientId) {
    return new User(uniqueEmail(), "not-a-real-hash", Role.PATIENT, patientId, Instant.now());
  }

  private static String constraintName(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation) {
        return violation.getConstraintName();
      }
    }
    return null;
  }

  private static String fullMessage(Throwable failure) {
    StringBuilder messages = new StringBuilder();
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      messages.append(cause).append('\n');
    }
    return messages.toString();
  }
}

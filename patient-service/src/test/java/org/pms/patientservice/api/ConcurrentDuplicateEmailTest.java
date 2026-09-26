package org.pms.patientservice.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;

/**
 * The service's email pre-check and insert are not atomic, so concurrent creates with the same
 * email can all pass the check. The database unique constraint decides the race; the losers must
 * get a 409, never a 500.
 */
@ExtendWith(OutputCaptureExtension.class)
class ConcurrentDuplicateEmailTest extends AbstractPatientApiTest {

  private static final int CONCURRENT_REQUESTS = 8;
  private static final long TIMEOUT_SECONDS = 30;

  @Test
  @DisplayName("concurrent creates with one email yield exactly one 201 and only 409s")
  void concurrentCreatesYieldOneCreatedAndOnlyConflicts(CapturedOutput output) throws Exception {
    String email = uniqueEmail();
    String body = patientJson(email, "1990-01-01", "2024-01-01");
    CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUESTS);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
    List<Integer> statuses = new ArrayList<>();
    try {
      List<Future<Integer>> responses = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
        responses.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return mockMvc
                      .perform(
                          post(PATIENTS)
                              .with(asAdmin())
                              .contentType(MediaType.APPLICATION_JSON)
                              .content(body))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                }));
      }
      assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (Future<Integer> response : responses) {
        statuses.add(response.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(statuses).containsOnly(201, 409);
    assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
    // Neither the handler nor the JDBC/Hibernate error logging may echo the email.
    assertThat(output.getAll()).doesNotContain(email);
  }
}

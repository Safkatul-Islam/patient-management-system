package org.pms.patientservice.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.patientservice.support.PostgresTestcontainerConfig;
import org.pms.patientservice.support.UnreachableBroker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Publishing must never hold a database connection. With the broker unreachable, a send blocks for
 * up to max.block.ms; if that happened on the request thread right after commit, each create would
 * keep its pooled connection meanwhile, and a small burst of creates would starve every other
 * request of connections. A deliberately tiny pool makes that visible: concurrent creates and reads
 * must all succeed. The producer is cold (no send before this test in this context).
 */
@SpringBootTest(
    properties = {
      "spring.datasource.hikari.maximum-pool-size=2",
      "spring.datasource.hikari.connection-timeout=1000"
    })
@AutoConfigureMockMvc
@Import(PostgresTestcontainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PatientCreatedPoolExhaustionTest {

  private static final int CREATES = 6;
  private static final int READS = 6;

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MeterRegistry meterRegistry;

  @DynamicPropertySource
  static void unreachableBroker(DynamicPropertyRegistry registry) {
    String address = UnreachableBroker.address();
    registry.add("spring.kafka.bootstrap-servers", () -> address);
  }

  @Test
  @DisplayName("creates while the broker is down do not starve concurrent requests of connections")
  void createsDoNotHoldConnectionsWhilePublishing() throws Exception {
    // Inserted directly, so no event is published (and the producer stays cold) before the burst.
    UUID existingId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO patients (id, name, email, address, date_of_birth, registered_date)"
            + " VALUES (?, 'Pool Reader', ?, '1 Pool St', DATE '1990-01-01', DATE '2024-01-01')",
        existingId,
        "pool-" + existingId + "@example.com");

    List<Callable<String>> requests = new ArrayList<>();
    for (int i = 0; i < CREATES; i++) {
      requests.add(() -> outcome("create", 201, createRequest()));
    }
    for (int i = 0; i < READS; i++) {
      MockHttpServletRequestBuilder read =
          i % 2 == 0 ? get("/api/v1/patients") : get("/api/v1/patients/" + existingId);
      requests.add(() -> outcome("read", 200, asAdmin(read)));
    }

    List<String> failures = runConcurrently(requests);

    assertThat(failures).as("requests that did not get their expected status").isEmpty();
    // Every create's event was attempted, failed, and counted; none vanished silently.
    await()
        .atMost(Duration.ofSeconds(60))
        .untilAsserted(() -> assertThat(publishFailures()).isEqualTo(CREATES));
  }

  /** Returns null on the expected status, otherwise a description of what happened instead. */
  private String outcome(String kind, int expected, MockHttpServletRequestBuilder request)
      throws Exception {
    MvcResult result = mockMvc.perform(request).andReturn();
    int status = result.getResponse().getStatus();
    if (status == expected) {
      return null;
    }
    Exception resolved = result.getResolvedException();
    return kind
        + " -> "
        + status
        + (resolved == null ? "" : " (" + resolved.getClass().getName() + ")");
  }

  private List<String> runConcurrently(List<Callable<String>> requests) throws Exception {
    CountDownLatch ready = new CountDownLatch(requests.size());
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(requests.size());
    try {
      List<Future<String>> futures = new ArrayList<>();
      for (Callable<String> request : requests) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return request.call();
                }));
      }
      assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<String> failures = new ArrayList<>();
      for (Future<String> future : futures) {
        String failure = future.get(60, TimeUnit.SECONDS);
        if (failure != null) {
          failures.add(failure);
        }
      }
      return failures;
    } finally {
      pool.shutdownNow();
    }
  }

  private static MockHttpServletRequestBuilder createRequest() {
    String email = "pool-" + UUID.randomUUID() + "@example.com";
    return asAdmin(post("/api/v1/patients"))
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"name":"Pool Patient","email":"%s","address":"2 Pool St",\
            "dateOfBirth":"1990-01-01","registeredDate":"2024-01-01"}"""
                .formatted(email));
  }

  private static MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
    return request
        .header("X-User-Id", UUID.randomUUID().toString())
        .header("X-User-Roles", "ADMIN");
  }

  private double publishFailures() {
    return meterRegistry.find(PatientCreatedPublisher.FAILURE_COUNTER).counters().stream()
        .mapToDouble(counter -> counter.count())
        .sum();
  }
}

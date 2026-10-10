package org.pms.analyticsservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.analyticsservice.event.PatientCreatedEvent;
import org.pms.analyticsservice.support.AbstractConsumerIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * Two deliveries of the same event handled at the same time (e.g. after a rebalance). The primary
 * key on processed_events serialises them: the second insert waits for the first transaction and
 * then sees its outcome.
 */
class ConcurrentDuplicateTest extends AbstractConsumerIntegrationTest {

  // Long enough that the other thread is certainly waiting on the row lock by then.
  private static final long EFFECT_DELAY_MS = 1000;

  @Autowired private PatientCreatedHandler handler;

  @Test
  @DisplayName("The effect cannot run outside the transaction that records the event")
  void effectRequiresTransaction() {
    LocalDate day = unusedDay();
    PatientCreatedEvent event =
        new PatientCreatedEvent(UUID.randomUUID(), noonOf(day), UUID.randomUUID());

    assertThatThrownBy(() -> dailyRegistrations.record(event))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(registrationsOn(day)).isZero();
  }

  @Test
  @DisplayName("Concurrent duplicates: exactly one applies the effect")
  void oneOfTwoConcurrentDuplicatesApplies() throws Exception {
    LocalDate day = unusedDay();
    PatientCreatedEvent event =
        new PatientCreatedEvent(UUID.randomUUID(), noonOf(day), UUID.randomUUID());
    doAnswer(
            invocation -> {
              Thread.sleep(EFFECT_DELAY_MS);
              return invocation.callRealMethod();
            })
        .when(dailyRegistrations)
        .record(argThat(e -> event.eventId().equals(e.eventId())));

    List<Object> outcomes = runTwiceConcurrently(() -> handler.handle(event));

    assertThat(outcomes).containsExactlyInAnyOrder(true, false);
    assertThat(registrationsOn(day)).isEqualTo(1);
    assertThat(isProcessed(event.eventId())).isTrue();
  }

  @Test
  @DisplayName("Concurrent duplicates: if the first rolls back, the second applies the effect")
  void secondAppliesAfterFirstRollsBack() throws Exception {
    LocalDate day = unusedDay();
    PatientCreatedEvent event =
        new PatientCreatedEvent(UUID.randomUUID(), noonOf(day), UUID.randomUUID());
    AtomicInteger calls = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 1) {
                Thread.sleep(EFFECT_DELAY_MS);
                throw new IllegalStateException("simulated effect failure");
              }
              return invocation.callRealMethod();
            })
        .when(dailyRegistrations)
        .record(argThat(e -> event.eventId().equals(e.eventId())));

    List<Object> outcomes = runTwiceConcurrently(() -> handler.handle(event));

    assertThat(outcomes)
        .hasSize(2)
        .contains(true)
        .anySatisfy(outcome -> assertThat(outcome).isInstanceOf(IllegalStateException.class));
    assertThat(calls).hasValue(2);
    assertThat(registrationsOn(day)).isEqualTo(1);
    assertThat(isProcessed(event.eventId())).isTrue();
  }

  /** Starts both calls together; each outcome is the returned value or the thrown exception. */
  private static List<Object> runTwiceConcurrently(Callable<Boolean> call) throws Exception {
    CyclicBarrier start = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Object>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        futures.add(
            executor.submit(
                () -> {
                  start.await(10, TimeUnit.SECONDS);
                  try {
                    return call.call();
                  } catch (RuntimeException e) {
                    return e;
                  }
                }));
      }
      List<Object> outcomes = new ArrayList<>();
      for (Future<Object> future : futures) {
        outcomes.add(future.get(30, TimeUnit.SECONDS));
      }
      return outcomes;
    } finally {
      executor.shutdownNow();
    }
  }
}

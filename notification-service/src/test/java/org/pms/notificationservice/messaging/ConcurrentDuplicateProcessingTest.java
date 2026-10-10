package org.pms.notificationservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pms.notificationservice.repository.ProcessedEventRepository;
import org.pms.notificationservice.service.PatientCreatedProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * The same event processed concurrently (e.g. by two consumers around a rebalance) against real
 * Postgres: the conflict-tolerant marker insert lets exactly one of them apply the effect. Calls
 * the processor directly; it extends the messaging base only to share its cached context.
 */
class ConcurrentDuplicateProcessingTest extends AbstractPatientCreatedIntegrationTest {

  private static final long WAIT_SECONDS = 30;

  @Autowired private PatientCreatedProcessor processor;
  @Autowired private ProcessedEventRepository processedEvents;

  private final ExecutorService executor = Executors.newFixedThreadPool(2);

  @AfterEach
  void stopExecutor() {
    executor.shutdownNow();
  }

  @Test
  @DisplayName("Two concurrent deliveries of one event: one applies it, the other skips it")
  void concurrentDuplicatesApplyOnce() throws Exception {
    PatientCreatedEvent event = newEvent();
    CyclicBarrier start = new CyclicBarrier(2);

    Future<Boolean> first = executor.submit(() -> processAfter(start, event));
    Future<Boolean> second = executor.submit(() -> processAfter(start, event));

    List<Boolean> results =
        List.of(
            first.get(WAIT_SECONDS, TimeUnit.SECONDS), second.get(WAIT_SECONDS, TimeUnit.SECONDS));
    assertThat(results).containsExactlyInAnyOrder(true, false);
    verify(recorder, times(1)).record(argThat(e -> e.eventId().equals(event.eventId())));
    assertThat(notificationsFor(event.eventId())).isEqualTo(1);
    assertThat(processedEventsFor(event.eventId())).isEqualTo(1);
  }

  @Test
  @DisplayName("If the first delivery fails and rolls back, the waiting second one applies it")
  void secondDeliveryAppliesAfterFirstRollsBack() throws Exception {
    PatientCreatedEvent event = newEvent();
    CountDownLatch firstHoldsMarker = new CountDownLatch(1);
    AtomicInteger effectCalls = new AtomicInteger();
    doAnswer(
            invocation -> {
              PatientCreatedEvent called = invocation.getArgument(0);
              if (called.eventId().equals(event.eventId()) && effectCalls.incrementAndGet() == 1) {
                // The first transaction has inserted the marker; give the second one time to
                // queue up behind that row, then fail so the marker rolls back.
                firstHoldsMarker.countDown();
                Thread.sleep(1000);
                throw new IllegalStateException("Simulated effect failure");
              }
              return invocation.callRealMethod();
            })
        .when(recorder)
        .record(any());

    Future<Boolean> first = executor.submit(() -> processor.process(event));
    assertThat(firstHoldsMarker.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
    Future<Boolean> second = executor.submit(() -> processor.process(event));

    assertThatThrownBy(() -> first.get(WAIT_SECONDS, TimeUnit.SECONDS))
        .hasCauseInstanceOf(IllegalStateException.class);
    assertThat(second.get(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
    assertThat(effectCalls).hasValue(2);
    assertThat(notificationsFor(event.eventId())).isEqualTo(1);
    assertThat(processedEventsFor(event.eventId())).isEqualTo(1);
  }

  @Test
  @DisplayName("Neither the dedupe marker nor the effect can be written outside a transaction")
  void markerAndEffectRequireTransaction() {
    PatientCreatedEvent event = newEvent();

    assertThatThrownBy(() -> recorder.record(event))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(notificationsFor(event.eventId())).isZero();
    assertThatThrownBy(
            () ->
                processedEvents.insertIfAbsent(UUID.randomUUID(), "patient.created", Instant.now()))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  private boolean processAfter(CyclicBarrier start, PatientCreatedEvent event) throws Exception {
    start.await(WAIT_SECONDS, TimeUnit.SECONDS);
    return processor.process(event);
  }

  private static PatientCreatedEvent newEvent() {
    return new PatientCreatedEvent(UUID.randomUUID(), Instant.now(), UUID.randomUUID());
  }
}

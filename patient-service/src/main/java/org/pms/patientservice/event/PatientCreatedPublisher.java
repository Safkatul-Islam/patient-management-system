package org.pms.patientservice.event;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.pms.common.web.correlation.CorrelationIds;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes {@code patient.created} once the transaction that created the patient has committed, so
 * a rolled-back create never produces an event.
 *
 * <p>The send runs on one dedicated background thread, never on the request thread: right after
 * commit the request still holds its pooled database connection, and a send can block for up to the
 * producer's {@code max.block.ms} while the broker is unreachable. Waiting events sit in a bounded
 * queue; when it is full an event is dropped rather than sent from the request thread.
 *
 * <p>The patient is already committed when this runs, so a failed publish cannot undo it and must
 * not fail the request either. Every lost event is logged once at ERROR with identifiers only and
 * counted in {@value #FAILURE_COUNTER}, tagged with why: the send failed (thrown by {@code send()}
 * or reported by the broker), the queue was full, or the service shut down before sending it.
 * Shutdown waits a bounded time for queued events. Events still queued when the process dies
 * without a clean shutdown are lost uncounted: the same accepted V1 loss as a crash between commit
 * and send (see PIPELINE.md).
 */
@Slf4j
@Component
public class PatientCreatedPublisher implements DisposableBean {

  static final String FAILURE_COUNTER = "pms.events.publish.failures";
  static final String SEND_FAILED = "send_failed";
  static final String QUEUE_FULL = "queue_full";
  static final String SHUTDOWN = "shutdown";

  // Bounds the root-cause walk in case an exception's causes form a cycle.
  private static final int MAX_CAUSE_DEPTH = 32;

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final JsonMapper jsonMapper;
  private final String topic;
  private final PatientEventPublisherProperties publisherProperties;
  private final Map<String, Counter> failuresByReason;
  private final ThreadPoolExecutor sender;

  public PatientCreatedPublisher(
      KafkaTemplate<String, String> kafkaTemplate,
      JsonMapper jsonMapper,
      PatientCreatedTopicProperties topicProperties,
      PatientEventPublisherProperties publisherProperties,
      MeterRegistry meterRegistry) {
    this.kafkaTemplate = kafkaTemplate;
    this.jsonMapper = jsonMapper;
    this.topic = topicProperties.topic();
    this.publisherProperties = publisherProperties;
    this.failuresByReason =
        Stream.of(SEND_FAILED, QUEUE_FULL, SHUTDOWN)
            .collect(
                Collectors.toUnmodifiableMap(
                    Function.identity(), reason -> failureCounter(meterRegistry, reason)));
    // One thread keeps sends off request threads without competing for the producer; the
    // default AbortPolicy rejects on overflow (CallerRunsPolicy would send on the request thread).
    this.sender =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(publisherProperties.queueCapacity()),
            senderThreads(),
            new ThreadPoolExecutor.AbortPolicy());
  }

  /**
   * Daemon on purpose: Spring owns the sender's lifecycle, and {@link #destroy()} drains and stops
   * it on every clean shutdown. A non-daemon thread would save no events (an exit that skips the
   * context close skips the drain too) but could keep alive a JVM whose context never closed. The
   * flag is set explicitly because a thread otherwise inherits it from whichever thread creates it,
   * here the first request thread to publish. Request-thread inheritable thread-locals stay behind.
   */
  private static ThreadFactory senderThreads() {
    return Thread.ofPlatform()
        .name("patient-events-sender")
        .daemon(true)
        .inheritInheritableThreadLocals(false)
        .uncaughtExceptionHandler(
            (thread, failure) ->
                // The failure was already logged and counted; never print its message here.
                log.error("Event sender thread ended by {}", failure.getClass().getName()))
        .factory();
  }

  /**
   * Only hands the event to the sender thread. {@code fallbackExecution = false} (the default, made
   * explicit): without a transaction there is no commit to wait for, so nothing is published.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = false)
  public void onPatientCreated(PatientCreated event) {
    try {
      sender.execute(new SendTask(event));
    } catch (RejectedExecutionException rejected) {
      recordFailure(event, sender.isShutdown() ? SHUTDOWN : QUEUE_FULL, rejected);
    }
  }

  /**
   * Stops accepting events, waits up to the drain timeout for queued ones, then interrupts the
   * sender and counts every event left unsent.
   */
  @Override
  public void destroy() {
    sender.shutdown();
    try {
      if (sender.awaitTermination(
          publisherProperties.drainTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
        return;
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    // Only SendTasks are ever queued.
    for (Runnable unsent : sender.shutdownNow()) {
      recordFailure(((SendTask) unsent).event(), SHUTDOWN, null);
    }
  }

  private void send(PatientCreated event) {
    try {
      kafkaTemplate
          .send(toRecord(event))
          .whenComplete(
              (result, failure) ->
                  withCorrelationId(
                      event,
                      () -> {
                        if (failure != null) {
                          recordFailure(event, SEND_FAILED, failure);
                        } else {
                          log.debug(
                              "Published patient.created eventId={} patientId={}",
                              event.eventId(),
                              event.patientId());
                        }
                      }));
    } catch (Exception failure) {
      // Building the record or send() itself threw. Never rethrown: it would only end the task.
      recordFailure(event, SEND_FAILED, failure);
    } catch (Error error) {
      // Counted like any lost event, then rethrown: an Error is not ours to swallow. It ends this
      // worker; the executor starts a fresh one for the next event.
      recordFailure(event, SEND_FAILED, error);
      throw error;
    }
  }

  private ProducerRecord<String, String> toRecord(PatientCreated event) {
    String payload = jsonMapper.writeValueAsString(PatientCreatedV1.from(event));
    RecordHeader correlationId =
        new RecordHeader(
            CorrelationIds.HEADER, event.correlationId().getBytes(StandardCharsets.UTF_8));
    return new ProducerRecord<>(
        topic, null, event.patientId().toString(), payload, List.of(correlationId));
  }

  /**
   * Logs exception types only: Kafka exception messages can be long and opaque, and the payload
   * must never be logged from here.
   */
  private void recordFailure(PatientCreated event, String reason, Throwable failure) {
    // Logged before counting, so whoever sees the count can also find the line.
    log.error(
        "Failed to publish patient.created eventId={} patientId={} reason={} errorType={}"
            + " causeType={}",
        event.eventId(),
        event.patientId(),
        reason,
        failure == null ? "none" : failure.getClass().getName(),
        failure == null ? "none" : rootCause(failure).getClass().getName());
    failuresByReason.get(reason).increment();
  }

  /** Runs {@code action} with the event's correlation ID in the MDC, then restores the MDC. */
  private static void withCorrelationId(PatientCreated event, Runnable action) {
    String previous = MDC.get(CorrelationIds.MDC_KEY);
    MDC.put(CorrelationIds.MDC_KEY, event.correlationId());
    try {
      action.run();
    } finally {
      if (previous == null) {
        MDC.remove(CorrelationIds.MDC_KEY);
      } else {
        MDC.put(CorrelationIds.MDC_KEY, previous);
      }
    }
  }

  private static Counter failureCounter(MeterRegistry meterRegistry, String reason) {
    return Counter.builder(FAILURE_COUNTER)
        .description("Domain events that could not be published to Kafka")
        .tag("eventType", PatientCreatedV1.EVENT_TYPE)
        .tag("reason", reason)
        .register(meterRegistry);
  }

  private static Throwable rootCause(Throwable failure) {
    Throwable cause = failure;
    for (int depth = 0; cause.getCause() != null && depth < MAX_CAUSE_DEPTH; depth++) {
      cause = cause.getCause();
    }
    return cause;
  }

  /**
   * A queued send, run with the originating request's correlation ID in the sender thread's MDC.
   * Kept as a type so shutdown can name the events it never sent.
   */
  private final class SendTask implements Runnable {

    private final PatientCreated event;

    private SendTask(PatientCreated event) {
      this.event = event;
    }

    private PatientCreated event() {
      return event;
    }

    @Override
    public void run() {
      withCorrelationId(event, () -> send(event));
    }
  }
}

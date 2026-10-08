package org.pms.notificationservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLTransientConnectionException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

class PatientCreatedBackOffsTest {

  private final BackOff connectivity = new ExponentialBackOff();
  private final BackOff other = new FixedBackOff();
  private final PatientCreatedBackOffs backOffs = new PatientCreatedBackOffs(connectivity, other);
  private final ConsumerRecord<String, String> record =
      new ConsumerRecord<>("patient.created", 0, 0L, "key", "value");

  @Test
  @DisplayName(
      "No transaction or connection, wrapped by the listener container: connectivity back-off")
  void connectivityFailuresWaitForTheDatabase() {
    assertThat(backOffs.apply(record, wrapped(new CannotCreateTransactionException("x"))))
        .isSameAs(connectivity);
    assertThat(
            backOffs.apply(
                record,
                wrapped(
                    new CannotGetJdbcConnectionException(
                        "x", new SQLTransientConnectionException()))))
        .isSameAs(connectivity);
    assertThat(backOffs.apply(record, wrapped(new CannotAcquireLockException("x"))))
        .isSameAs(connectivity);
  }

  @Test
  @DisplayName("A connectivity failure deeper in the cause chain still counts")
  void connectivityFailureAsCause() {
    Exception nested =
        new IllegalStateException("outer", new CannotCreateTransactionException("inner"));

    assertThat(backOffs.apply(record, wrapped(nested))).isSameAs(connectivity);
  }

  @Test
  @DisplayName("Any other failure: the short default back-off")
  void otherFailuresUseDefault() {
    assertThat(backOffs.apply(record, wrapped(new IllegalStateException("x")))).isSameAs(other);
    assertThat(backOffs.apply(record, wrapped(new DataIntegrityViolationException("x"))))
        .isSameAs(other);
  }

  private static Exception wrapped(Exception cause) {
    return new ListenerExecutionFailedException("Listener failed", cause);
  }
}

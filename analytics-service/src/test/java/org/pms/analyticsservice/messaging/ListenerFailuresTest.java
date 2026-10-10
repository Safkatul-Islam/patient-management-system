package org.pms.analyticsservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;

class ListenerFailuresTest {

  @Test
  @DisplayName("Connectivity and transient data-access failures are found anywhere in the chain")
  void connectivityFailures() {
    assertThat(
            ListenerFailures.isConnectivityFailure(
                wrapped(
                    new CannotCreateTransactionException(
                        "Could not open JPA EntityManager",
                        new SQLTransientConnectionException()))))
        .isTrue();
    assertThat(
            ListenerFailures.isConnectivityFailure(
                wrapped(new CannotGetJdbcConnectionException("no connection"))))
        .isTrue();
    assertThat(ListenerFailures.isConnectivityFailure(wrapped(new QueryTimeoutException("slow"))))
        .isTrue();
    assertThat(
            ListenerFailures.isConnectivityFailure(
                new IllegalStateException("outer", new CannotGetJdbcConnectionException("x"))))
        .isTrue();
  }

  @Test
  @DisplayName("Other failures keep the default back-off")
  void otherFailures() {
    assertThat(ListenerFailures.isConnectivityFailure(wrapped(new IllegalStateException("x"))))
        .isFalse();
    assertThat(
            ListenerFailures.isConnectivityFailure(
                wrapped(new DataIntegrityViolationException("constraint"))))
        .isFalse();
    // A raw JDBC exception that Spring did not translate is not classified by itself.
    assertThat(ListenerFailures.isConnectivityFailure(new SQLTransientConnectionException()))
        .isFalse();
  }

  @Test
  @DisplayName("A cause cycle does not loop forever")
  void causeCycle() {
    IllegalStateException first = new IllegalStateException("first");
    IllegalArgumentException second = new IllegalArgumentException("second", first);
    first.initCause(second);

    assertThat(ListenerFailures.isConnectivityFailure(first)).isFalse();
    assertThat(ListenerFailures.rootCause(first)).isNotNull();
  }

  @Test
  @DisplayName("The container's wrapper is removed to name the listener's own exception")
  void unwrapsListenerFailure() {
    IllegalStateException cause = new IllegalStateException("x");

    assertThat(ListenerFailures.listenerFailure(wrapped(cause))).isSameAs(cause);
    assertThat(ListenerFailures.listenerFailure(cause)).isSameAs(cause);
  }

  private static ListenerExecutionFailedException wrapped(Exception cause) {
    return new ListenerExecutionFailedException("Listener failed", cause);
  }
}

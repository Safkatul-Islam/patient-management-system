package org.pms.analyticsservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.orm.jpa.JpaSystemException;
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
  @DisplayName("A socket timeout anywhere in the chain is a connectivity failure")
  void socketTimeout() {
    assertThat(
            ListenerFailures.isConnectivityFailure(
                wrapped(jpaFailure(new SocketTimeoutException("Read timed out")))))
        .isTrue();
  }

  @Test
  @DisplayName(
      "A SQLException with an 08xxx SQLState anywhere in the chain is a connectivity failure")
  void connectionExceptionSqlState() {
    assertThat(ListenerFailures.isConnectivityFailure(wrapped(jpaFailure(sqlState("08006")))))
        .isTrue();
    assertThat(ListenerFailures.isConnectivityFailure(wrapped(jpaFailure(sqlState("08001")))))
        .isTrue();
  }

  @Test
  @DisplayName("Other SQLStates, a missing SQLState and other I/O errors are not connectivity")
  void onlyTheTwoNewSignals() {
    assertThat(ListenerFailures.isConnectivityFailure(wrapped(jpaFailure(sqlState("23505")))))
        .isFalse();
    assertThat(ListenerFailures.isConnectivityFailure(wrapped(jpaFailure(sqlState(null)))))
        .isFalse();
    assertThat(
            ListenerFailures.isConnectivityFailure(
                wrapped(jpaFailure(new ConnectException("refused")))))
        .isFalse();
    assertThat(ListenerFailures.isConnectivityFailure(wrapped(jpaFailure(new IOException("io")))))
        .isFalse();
  }

  @Test
  @DisplayName(
      "The walk stops after 32 levels: a signal at level 32 counts, at level 33 it does not")
  void depthCap() {
    assertThat(ListenerFailures.isConnectivityFailure(withWrappers(31, sqlState("08006"))))
        .isTrue();
    assertThat(ListenerFailures.isConnectivityFailure(withWrappers(32, sqlState("08006"))))
        .isFalse();
    assertThat(
            ListenerFailures.isConnectivityFailure(
                withWrappers(32, new SocketTimeoutException("deep"))))
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

  /** The shape of a statement that failed on a dead connection: JPA's wrapper around the cause. */
  private static JpaSystemException jpaFailure(Throwable cause) {
    return new JpaSystemException(new RuntimeException("Unable to commit", cause));
  }

  private static SQLException sqlState(String state) {
    return new SQLException("database error", state);
  }

  /** {@code wrappers} levels above {@code innermost}, which sits at level {@code wrappers + 1}. */
  private static Throwable withWrappers(int wrappers, Throwable innermost) {
    Throwable current = innermost;
    for (int level = 0; level < wrappers; level++) {
      current = new IllegalStateException("level " + level, current);
    }
    return current;
  }
}

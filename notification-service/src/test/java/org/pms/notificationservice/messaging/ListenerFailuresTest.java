package org.pms.notificationservice.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;

class ListenerFailuresTest {

  @Test
  @DisplayName("Spring's connectivity exceptions are still found anywhere in the chain")
  void springConnectivityExceptions() {
    assertThat(
            ListenerFailures.isConnectivityFailure(
                wrapped(new CannotCreateTransactionException("no connection"))))
        .isTrue();
    assertThat(ListenerFailures.isConnectivityFailure(wrapped(new IllegalStateException("x"))))
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

    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () -> assertThat(ListenerFailures.isConnectivityFailure(first)).isFalse());
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

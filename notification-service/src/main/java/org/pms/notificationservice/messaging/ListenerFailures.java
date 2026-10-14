package org.pms.notificationservice.messaging;

import java.net.SocketTimeoutException;
import java.sql.SQLException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;

/** Classification of exceptions thrown by the patient.created listener. */
final class ListenerFailures {

  // Bounds every cause-chain walk in case an exception's causes form a cycle.
  private static final int MAX_CAUSE_DEPTH = 32;

  private ListenerFailures() {}

  /**
   * The class of the listener's own exception, unwrapped from the container's wrapper. Only the
   * class is reported, never the message, which could quote payload or row values.
   */
  static String typeOf(Exception exception) {
    Throwable failure = exception;
    if (failure instanceof ListenerExecutionFailedException && failure.getCause() != null) {
      failure = failure.getCause();
    }
    return failure.getClass().getName();
  }

  /**
   * Whether the failure, anywhere in its cause chain, is this service's database being unreachable
   * or transiently failing: no connection, no transaction, an error Spring classifies as transient,
   * a socket read that timed out, or a JDBC error in SQLState class 08 (connection exception). Such
   * a failure says nothing about the event itself, so it must be waited out. The walk is bounded,
   * so a cause cycle cannot loop forever.
   */
  static boolean isConnectivityFailure(Throwable exception) {
    Throwable cause = exception;
    for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
      if (cause instanceof TransientDataAccessException
          || cause instanceof DataAccessResourceFailureException
          || cause instanceof CannotCreateTransactionException
          || cause instanceof SocketTimeoutException
          || isConnectionExceptionState(cause)) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  /** SQLState class 08 is "connection exception" in the SQL standard (e.g. 08006, 08001). */
  private static boolean isConnectionExceptionState(Throwable cause) {
    return cause instanceof SQLException sql
        && sql.getSQLState() != null
        && sql.getSQLState().startsWith("08");
  }
}

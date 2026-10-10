package org.pms.notificationservice.messaging;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;

/** Classification of exceptions thrown by the patient.created listener. */
final class ListenerFailures {

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
   * or transiently failing: no connection, no transaction, or an error Spring classifies as
   * transient. Such a failure says nothing about the event itself, so it must be waited out.
   */
  static boolean isConnectivityFailure(Throwable exception) {
    for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
      if (cause instanceof TransientDataAccessException
          || cause instanceof DataAccessResourceFailureException
          || cause instanceof CannotCreateTransactionException) {
        return true;
      }
      if (cause.getCause() == cause) {
        return false;
      }
    }
    return false;
  }
}

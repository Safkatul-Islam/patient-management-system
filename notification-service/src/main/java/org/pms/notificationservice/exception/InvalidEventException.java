package org.pms.notificationservice.exception;

/**
 * A record that can never be processed as it is: not JSON, or not a {@code patient.created} version
 * 1 event. Retrying cannot help, so the error handler sends it straight to the dead-letter topic.
 *
 * <p>Messages are fixed descriptions of what is wrong and never quote the payload, and no parser
 * exception is chained as the cause: its message can echo payload text, and listener-container
 * error logs print the whole cause chain.
 */
public class InvalidEventException extends RuntimeException {

  public InvalidEventException(String reason) {
    super(reason);
  }
}

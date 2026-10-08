package org.pms.analyticsservice.event;

/**
 * A record that is not a usable {@code patient.created} v1 event. Retrying cannot fix it, so the
 * error handler dead-letters it immediately.
 *
 * <p>Messages are fixed descriptions of what was wrong and never quote the payload: they end up in
 * logs and dead-letter headers.
 */
public class InvalidEventException extends RuntimeException {

  public InvalidEventException(String message) {
    super(message);
  }
}

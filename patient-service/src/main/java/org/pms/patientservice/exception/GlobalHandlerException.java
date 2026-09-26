package org.pms.patientservice.exception;

import java.time.format.DateTimeParseException;
import lombok.extern.slf4j.Slf4j;
import org.pms.common.web.problem.ProblemDetailsExceptionHandler;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/**
 * Maps patient-service domain exceptions to RFC 7807 problem responses. Validation, malformed
 * bodies and unexpected errors are handled by the shared base class.
 *
 * <p>Nothing here logs or returns request content (email, dates of birth): it is patient data.
 */
@Slf4j
@RestControllerAdvice
public class GlobalHandlerException extends ProblemDetailsExceptionHandler {

  @ExceptionHandler(PatientNotFoundException.class)
  public ResponseEntity<Object> handlePatientNotFound(
      PatientNotFoundException ex, WebRequest request) {
    log.warn("{}", ex.getMessage());
    return respond(
        ex,
        HttpStatus.NOT_FOUND,
        "Patient not found",
        "No patient exists with the requested ID.",
        request);
  }

  @ExceptionHandler(EmailAlreadyExistsException.class)
  public ResponseEntity<Object> handleEmailAlreadyExists(
      EmailAlreadyExistsException ex, WebRequest request) {
    log.warn("Rejected patient write: email already in use");
    return respond(
        ex,
        HttpStatus.CONFLICT,
        "Email already in use",
        "Another patient is already registered with this email.",
        request);
  }

  @ExceptionHandler(DateTimeParseException.class)
  public ResponseEntity<Object> handleDateTimeParse(DateTimeParseException ex, WebRequest request) {
    // The exception message echoes the rejected input, which may be a date of birth.
    log.warn("Rejected patient write: unparseable date");
    return respond(
        ex,
        HttpStatus.BAD_REQUEST,
        "Invalid date",
        "Invalid date format. Expected ISO-8601 (yyyy-MM-dd).",
        request);
  }

  @ExceptionHandler(InvalidSortPropertyException.class)
  public ResponseEntity<Object> handleInvalidSortProperty(
      InvalidSortPropertyException ex, WebRequest request) {
    // The message is built from the allow-list only, so it is safe to return.
    return respond(ex, HttpStatus.BAD_REQUEST, "Invalid sort parameter", ex.getMessage(), request);
  }

  /** Safety net for a sort that reaches Spring Data without passing the service allow-list. */
  @ExceptionHandler(PropertyReferenceException.class)
  public ResponseEntity<Object> handlePropertyReference(
      PropertyReferenceException ex, WebRequest request) {
    // The exception message names internal types, so it stays out of the response.
    log.warn("Rejected request: sort property not found on entity");
    return respond(
        ex,
        HttpStatus.BAD_REQUEST,
        "Invalid sort parameter",
        "Unsupported sort property.",
        request);
  }
}

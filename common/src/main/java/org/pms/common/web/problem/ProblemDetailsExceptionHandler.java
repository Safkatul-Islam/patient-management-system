package org.pms.common.web.problem;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.pms.common.web.correlation.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Shared RFC 7807 ({@code application/problem+json}) error handling for servlet services.
 *
 * <p>Each service declares one {@code @RestControllerAdvice} that extends this class and adds
 * handlers for its own domain exceptions via {@link #respond}. Framework exceptions (validation,
 * unreadable body, 404/405/415, ...) are handled here, anything unexpected becomes a generic 500,
 * and every problem body carries the request's {@code correlationId}.
 *
 * <p>The catch-all 500 handler also catches exceptions thrown from controllers by Spring Security
 * method security. Services that use {@code @PreAuthorize} must map {@code AccessDeniedException} /
 * {@code AuthenticationException} explicitly in their subclass.
 */
public abstract class ProblemDetailsExceptionHandler extends ResponseEntityExceptionHandler {

  public static final String CORRELATION_ID_PROPERTY = Problems.CORRELATION_ID_PROPERTY;
  public static final String ERRORS_PROPERTY = "errors";

  private static final Logger log = LoggerFactory.getLogger(ProblemDetailsExceptionHandler.class);

  /** One invalid input value; {@code field} is the object name for class-level constraints. */
  public record FieldViolation(String field, String message) {}

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail problem =
        problem(HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid.");
    problem.setProperty(ERRORS_PROPERTY, violations(ex.getBindingResult()));
    return handleExceptionInternal(ex, problem, headers, status, request);
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail problem =
        problem(
            HttpStatus.BAD_REQUEST,
            "Malformed request body",
            "The request body could not be read.");
    return handleExceptionInternal(ex, problem, headers, status, request);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
    // Full detail stays server-side; the caller only gets a generic body.
    log.error("Unhandled exception", ex);
    return respond(
        ex,
        HttpStatus.INTERNAL_SERVER_ERROR,
        "Internal server error",
        "An unexpected error occurred.",
        request);
  }

  /** Builds and returns a problem response; for use by service-specific handlers. */
  protected ResponseEntity<Object> respond(
      Exception ex, HttpStatus status, String title, String detail, WebRequest request) {
    return handleExceptionInternal(
        ex, problem(status, title, detail), new HttpHeaders(), status, request);
  }

  protected static ProblemDetail problem(HttpStatus status, String title, String detail) {
    return Problems.create(status, title, detail);
  }

  @Override
  protected ResponseEntity<Object> createResponseEntity(
      Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
    String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
    if (body instanceof ProblemDetail problem && correlationId != null) {
      problem.setProperty(CORRELATION_ID_PROPERTY, correlationId);
    }
    return super.createResponseEntity(body, headers, statusCode, request);
  }

  private static List<FieldViolation> violations(BindingResult result) {
    Stream<FieldViolation> fieldViolations =
        result.getFieldErrors().stream()
            .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()));
    Stream<FieldViolation> objectViolations =
        result.getGlobalErrors().stream()
            .map(error -> new FieldViolation(error.getObjectName(), error.getDefaultMessage()));
    return Stream.concat(fieldViolations, objectViolations)
        .sorted(Comparator.comparing(FieldViolation::field))
        .toList();
  }
}

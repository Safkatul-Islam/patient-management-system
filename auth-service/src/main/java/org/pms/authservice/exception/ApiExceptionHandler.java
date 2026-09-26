package org.pms.authservice.exception;

import java.util.List;
import org.pms.common.web.problem.ProblemDetailsExceptionHandler;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

/** auth-service's RFC 7807 mappings on top of the shared handler. */
@RestControllerAdvice
public class ApiExceptionHandler extends ProblemDetailsExceptionHandler {

  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<Object> handleInvalidCredentials(
      InvalidCredentialsException ex, WebRequest request) {
    return respond(ex, HttpStatus.UNAUTHORIZED, "Unauthorized", "Invalid credentials.", request);
  }

  @ExceptionHandler(InvalidRefreshTokenException.class)
  public ResponseEntity<Object> handleInvalidRefreshToken(
      InvalidRefreshTokenException ex, WebRequest request) {
    return respond(
        ex,
        HttpStatus.UNAUTHORIZED,
        "Unauthorized",
        "The refresh token is invalid or expired.",
        request);
  }

  @ExceptionHandler(EmailAlreadyInUseException.class)
  public ResponseEntity<Object> handleEmailAlreadyInUse(
      EmailAlreadyInUseException ex, WebRequest request) {
    return respond(ex, HttpStatus.CONFLICT, "Conflict", "Email already in use.", request);
  }

  /** Same body shape as a bean-validation failure, so clients handle both one way. */
  @ExceptionHandler(InvalidAccountRequestException.class)
  public ResponseEntity<Object> handleInvalidAccountRequest(
      InvalidAccountRequestException ex, WebRequest request) {
    ProblemDetail problem =
        problem(HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid.");
    problem.setProperty(
        ERRORS_PROPERTY, List.of(new FieldViolation(ex.getField(), ex.getMessage())));
    return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
  }
}

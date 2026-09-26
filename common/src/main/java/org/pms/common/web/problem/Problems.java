package org.pms.common.web.problem;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * The one definition of this system's RFC 7807 body: {@code type/title/status/detail/instance} plus
 * a {@code correlationId} extension member. Used by the MVC exception handler, the servlet security
 * handlers and the gateway's reactive error handlers, so every service and failure path emits the
 * same shape.
 */
public final class Problems {

  public static final String CORRELATION_ID_PROPERTY = "correlationId";

  private Problems() {}

  public static ProblemDetail create(HttpStatus status, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    return problem;
  }

  /**
   * A problem for writers outside Spring MVC (security filter chains, reactive handlers), which
   * serialize the body themselves: sets {@code instance} and, when known, the correlation ID.
   */
  public static ProblemDetail create(
      HttpStatus status, String title, String detail, String path, String correlationId) {
    ProblemDetail problem = create(status, title, detail);
    problem.setInstance(URI.create(path));
    if (correlationId != null) {
      problem.setProperty(CORRELATION_ID_PROPERTY, correlationId);
    }
    return problem;
  }

  /**
   * Flattens a problem into the wire shape, extension members at the top level. For writers that
   * serialize with a plain JSON mapper rather than Spring's HTTP message converters (which apply
   * the equivalent flattening themselves).
   */
  public static Map<String, Object> toBody(ProblemDetail problem) {
    Map<String, Object> body = new LinkedHashMap<>();
    // Framework 7 leaves type unset (null) for the RFC 7807 default; "about:blank" is its meaning.
    body.put("type", problem.getType() != null ? problem.getType().toString() : "about:blank");
    body.put("title", problem.getTitle());
    body.put("status", problem.getStatus());
    body.put("detail", problem.getDetail());
    if (problem.getInstance() != null) {
      body.put("instance", problem.getInstance().toString());
    }
    if (problem.getProperties() != null) {
      body.putAll(problem.getProperties());
    }
    return body;
  }
}

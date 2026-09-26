package org.pms.authservice.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.pms.common.web.correlation.CorrelationIdFilter;
import org.pms.common.web.problem.ProblemDetailsExceptionHandler;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes RFC 7807 bodies for failures raised inside the security filter chain, where the MVC
 * exception handler never runs. Produces the same shape as common's {@link
 * ProblemDetailsExceptionHandler}: type/title/status/detail/instance plus {@code correlationId}.
 */
@Component
class SecurityProblemWriter {

  private final JsonMapper jsonMapper;

  SecurityProblemWriter(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  void write(
      HttpServletRequest request,
      HttpServletResponse response,
      HttpStatus status,
      String title,
      String detail)
      throws IOException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "about:blank");
    body.put("title", title);
    body.put("status", status.value());
    body.put("detail", detail);
    body.put("instance", request.getRequestURI());
    String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
    if (correlationId != null) {
      body.put(ProblemDetailsExceptionHandler.CORRELATION_ID_PROPERTY, correlationId);
    }
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    jsonMapper.writeValue(response.getOutputStream(), body);
  }
}

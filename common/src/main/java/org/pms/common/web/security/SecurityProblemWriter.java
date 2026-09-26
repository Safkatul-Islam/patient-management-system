package org.pms.common.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.pms.common.web.correlation.CorrelationIds;
import org.pms.common.web.problem.Problems;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes RFC 7807 bodies for failures raised inside a servlet security filter chain, where the MVC
 * exception handler never runs. Same shape as every other problem response (see {@link Problems}).
 */
public class SecurityProblemWriter {

  private final JsonMapper jsonMapper;

  public SecurityProblemWriter(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  public void write(
      HttpServletRequest request,
      HttpServletResponse response,
      HttpStatus status,
      String title,
      String detail)
      throws IOException {
    var problem =
        Problems.create(
            status, title, detail, request.getRequestURI(), MDC.get(CorrelationIds.MDC_KEY));
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    jsonMapper.writeValue(response.getOutputStream(), Problems.toBody(problem));
  }
}

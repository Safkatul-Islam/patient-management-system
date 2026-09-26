package org.pms.authservice.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** 403 for an authenticated caller whose role does not permit the request. */
@Component
class ProblemAccessDeniedHandler implements AccessDeniedHandler {

  private final SecurityProblemWriter problemWriter;

  ProblemAccessDeniedHandler(SecurityProblemWriter problemWriter) {
    this.problemWriter = problemWriter;
  }

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException accessDeniedException)
      throws IOException {
    problemWriter.write(
        request,
        response,
        HttpStatus.FORBIDDEN,
        "Forbidden",
        "You do not have permission to perform this action.");
  }
}

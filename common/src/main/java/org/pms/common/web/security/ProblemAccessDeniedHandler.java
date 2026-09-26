package org.pms.common.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/** 403 as an RFC 7807 body, for an authenticated caller whose role does not permit the request. */
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

  private final SecurityProblemWriter problemWriter;

  public ProblemAccessDeniedHandler(SecurityProblemWriter problemWriter) {
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

package org.pms.common.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * 401 as an RFC 7807 body. The detail is fixed per service and generic on purpose: why
 * authentication failed (bad signature, wrong issuer, malformed identity header, ...) is never
 * disclosed to the caller.
 *
 * <p>The {@code WWW-Authenticate} challenge is supplied by the service, since only it knows its
 * scheme (e.g. a bearer resource server distinguishes a rejected token from a missing one).
 */
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final SecurityProblemWriter problemWriter;
  private final Function<AuthenticationException, String> challenge;
  private final String detail;

  public ProblemAuthenticationEntryPoint(
      SecurityProblemWriter problemWriter,
      Function<AuthenticationException, String> challenge,
      String detail) {
    this.problemWriter = problemWriter;
    this.challenge = challenge;
    this.detail = detail;
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge.apply(authException));
    problemWriter.write(request, response, HttpStatus.UNAUTHORIZED, "Unauthorized", detail);
  }
}

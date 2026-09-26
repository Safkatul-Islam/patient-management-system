package org.pms.authservice.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 401 for a missing, malformed, expired or otherwise rejected bearer token. The body is generic on
 * purpose: why a token failed (bad signature, wrong issuer, ...) is not disclosed to the caller.
 */
@Component
class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final SecurityProblemWriter problemWriter;

  ProblemAuthenticationEntryPoint(SecurityProblemWriter problemWriter) {
    this.problemWriter = problemWriter;
  }

  @Override
  public void commence(
      HttpServletRequest request,
      HttpServletResponse response,
      AuthenticationException authException)
      throws IOException {
    // RFC 6750: a presented-but-rejected token gets error="invalid_token"; no token, bare scheme.
    String challenge =
        authException instanceof OAuth2AuthenticationException
            ? "Bearer error=\"invalid_token\""
            : "Bearer";
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
    problemWriter.write(
        request,
        response,
        HttpStatus.UNAUTHORIZED,
        "Unauthorized",
        "A valid bearer access token is required.");
  }
}

package org.pms.apigateway.security;

import org.pms.apigateway.error.ProblemResponseWriter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 401 as an RFC 7807 body. RFC 6750: a presented-but-rejected token gets {@code
 * error="invalid_token"}, a missing token the bare scheme. Why a token was rejected is never
 * disclosed.
 */
public class ProblemServerAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

  private final ProblemResponseWriter writer;

  public ProblemServerAuthenticationEntryPoint(ProblemResponseWriter writer) {
    this.writer = writer;
  }

  @Override
  public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
    String challenge =
        ex instanceof OAuth2AuthenticationException ? "Bearer error=\"invalid_token\"" : "Bearer";
    exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, challenge);
    return writer.write(
        exchange,
        HttpStatus.UNAUTHORIZED,
        "Unauthorized",
        "A valid bearer access token is required.");
  }
}

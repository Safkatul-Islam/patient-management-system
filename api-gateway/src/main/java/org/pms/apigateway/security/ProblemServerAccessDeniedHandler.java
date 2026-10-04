package org.pms.apigateway.security;

import org.pms.apigateway.error.ProblemResponseWriter;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 403 as an RFC 7807 body. The gateway checks no roles, so this is reached only by an authenticated
 * caller on a path no route exposes (the fail-closed {@code denyAll} rule).
 */
public class ProblemServerAccessDeniedHandler implements ServerAccessDeniedHandler {

  private final ProblemResponseWriter writer;

  public ProblemServerAccessDeniedHandler(ProblemResponseWriter writer) {
    this.writer = writer;
  }

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException denied) {
    return writer.write(
        exchange,
        HttpStatus.FORBIDDEN,
        "Forbidden",
        "You do not have permission to perform this action.");
  }
}

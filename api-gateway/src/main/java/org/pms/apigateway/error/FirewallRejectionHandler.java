package org.pms.apigateway.error;

import org.pms.common.web.problem.Problems;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.server.firewall.ServerExchangeRejectedException;
import org.springframework.security.web.server.firewall.ServerExchangeRejectedHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Answers requests that Spring Security's firewall rejects (encoded or dot-segment paths, {@code
 * //}, {@code ;} parameters, backslashes, ...) with the system's RFC 7807 body instead of the
 * default bare 400.
 *
 * <p>The problem deliberately has no {@code instance}: the rejected path is attacker-controlled, so
 * it is neither echoed back nor logged (its malformed characters are also not guaranteed to be a
 * valid URI). Neither is the exception message, which quotes the offending input.
 */
public class FirewallRejectionHandler implements ServerExchangeRejectedHandler {

  private static final Logger log = LoggerFactory.getLogger(FirewallRejectionHandler.class);

  private final ProblemResponseWriter writer;

  public FirewallRejectionHandler(ProblemResponseWriter writer) {
    this.writer = writer;
  }

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, ServerExchangeRejectedException rejection) {
    log.debug("Request rejected by the security firewall");
    ProblemDetail problem =
        Problems.create(HttpStatus.BAD_REQUEST, "Bad request", "The request path is not allowed.");
    String correlationId = ProblemResponseWriter.correlationId(exchange);
    if (correlationId != null) {
      problem.setProperty(Problems.CORRELATION_ID_PROPERTY, correlationId);
    }
    return writer.write(exchange, problem);
  }
}

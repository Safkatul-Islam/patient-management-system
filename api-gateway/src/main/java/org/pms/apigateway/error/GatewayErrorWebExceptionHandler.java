package org.pms.apigateway.error;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import org.pms.apigateway.security.jwks.JwksUnavailableException;
import org.pms.common.web.correlation.CorrelationIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Renders failures raised by the gateway itself (not downstream error responses, which pass through
 * untouched) as RFC 7807 bodies:
 *
 * <ul>
 *   <li>no signing keys could ever be fetched: 503 (authentication temporarily unavailable);
 *   <li>downstream unreachable (connection refused, connect timeout, unknown host): 503;
 *   <li>downstream response timeout: 504;
 *   <li>other framework status errors (e.g. 404): that status, with a generic body;
 *   <li>anything else: a generic 500.
 * </ul>
 *
 * Bodies never contain exception messages, class names, host names or downstream URLs. Logs name
 * exception types only, because messages can embed arbitrary request data.
 */
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler, Ordered {

  /** Ahead of Boot's default handler (-1), which backs off once this bean exists anyway. */
  public static final int ORDER = -2;

  private static final Logger log = LoggerFactory.getLogger(GatewayErrorWebExceptionHandler.class);
  private static final int MAX_CAUSE_DEPTH = 16;

  private final ProblemResponseWriter writer;

  public GatewayErrorWebExceptionHandler(ProblemResponseWriter writer) {
    this.writer = writer;
  }

  private record Failure(HttpStatus status, String title, String detail) {}

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
    if (exchange.getResponse().isCommitted()) {
      return Mono.error(ex);
    }
    Failure failure = classify(ex);
    logFailure(exchange, failure, ex);
    resetHeaders(exchange);
    return writer.write(exchange, failure.status(), failure.title(), failure.detail());
  }

  private static Failure classify(Throwable ex) {
    if (hasCause(ex, JwksUnavailableException.class)) {
      return new Failure(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Authentication temporarily unavailable",
          "Access tokens cannot be verified right now. Please retry later.");
    }
    if (ex instanceof ResponseStatusException status
        && status.getStatusCode().value() == HttpStatus.GATEWAY_TIMEOUT.value()) {
      return new Failure(
          HttpStatus.GATEWAY_TIMEOUT,
          "Gateway timeout",
          "The upstream service did not respond in time.");
    }
    if (hasCause(ex, ConnectException.class)
        || hasCause(ex, UnknownHostException.class)
        || hasCause(ex, NoRouteToHostException.class)) {
      return new Failure(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Service unavailable",
          "The upstream service is unavailable. Please retry later.");
    }
    if (ex instanceof ResponseStatusException status) {
      HttpStatus resolved = HttpStatus.resolve(status.getStatusCode().value());
      if (resolved != null) {
        return new Failure(
            resolved, resolved.getReasonPhrase(), "The request could not be processed.");
      }
    }
    return new Failure(
        HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", "An unexpected error occurred.");
  }

  private static void logFailure(ServerWebExchange exchange, Failure failure, Throwable ex) {
    if (failure.status().is4xxClientError()) {
      return;
    }
    // This handler runs outside the correlation filter's Reactor context, so set the MDC here.
    String correlationId = ProblemResponseWriter.correlationId(exchange);
    try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationIds.MDC_KEY, correlationId)) {
      if (failure.status() == HttpStatus.INTERNAL_SERVER_ERROR) {
        log.error("Unhandled gateway error: {}", causeTypes(ex));
      } else {
        log.warn(
            "Request failed with {}: route={} cause={}",
            failure.status().value(),
            routeId(exchange),
            causeTypes(ex));
      }
    }
  }

  /** Drops any headers a failed proxy attempt left behind; keeps the correlation ID. */
  private static void resetHeaders(ServerWebExchange exchange) {
    HttpHeaders headers = exchange.getResponse().getHeaders();
    String correlationId = headers.getFirst(CorrelationIds.HEADER);
    headers.clear();
    if (correlationId != null) {
      headers.set(CorrelationIds.HEADER, correlationId);
    }
  }

  private static String routeId(ServerWebExchange exchange) {
    Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
    return route != null ? route.getId() : "none";
  }

  private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
    return causeChain(ex).stream().anyMatch(type::isInstance);
  }

  private static String causeTypes(Throwable ex) {
    return String.join(
        " <- ", causeChain(ex).stream().map(cause -> cause.getClass().getName()).toList());
  }

  /** The exception and its causes, bounded so a cyclic cause chain cannot loop forever. */
  private static List<Throwable> causeChain(Throwable ex) {
    List<Throwable> chain = new ArrayList<>();
    for (Throwable current = ex;
        current != null && chain.size() < MAX_CAUSE_DEPTH && !chain.contains(current);
        current = current.getCause()) {
      chain.add(current);
    }
    return chain;
  }

  @Override
  public int getOrder() {
    return ORDER;
  }
}

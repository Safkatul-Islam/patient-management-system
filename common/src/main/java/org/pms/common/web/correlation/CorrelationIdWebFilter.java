package org.pms.common.web.correlation;

import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive counterpart of {@link CorrelationIdFilter}. The resolved ID replaces the inbound header
 * on the request (so anything the request is forwarded to receives the validated value, never a
 * malformed one), is echoed on the response, and is written to the Reactor context under {@value
 * CorrelationIds#MDC_KEY}. With automatic context propagation enabled ({@code
 * spring.reactor.context-propagation=auto}) and {@link MdcCorrelationIdAccessor} registered, it
 * then appears in the MDC, and so in structured logs, on whichever thread handles the request.
 */
public class CorrelationIdWebFilter implements WebFilter, Ordered {

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String correlationId =
        CorrelationIds.resolve(exchange.getRequest().getHeaders().getFirst(CorrelationIds.HEADER));
    ServerHttpRequest request =
        exchange
            .getRequest()
            .mutate()
            .headers(headers -> headers.set(CorrelationIds.HEADER, correlationId))
            .build();
    exchange.getResponse().getHeaders().set(CorrelationIds.HEADER, correlationId);
    return chain
        .filter(exchange.mutate().request(request).build())
        .contextWrite(context -> context.put(CorrelationIds.MDC_KEY, correlationId));
  }

  /** First, ahead of Spring Security, so even rejected requests are correlated. */
  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }
}

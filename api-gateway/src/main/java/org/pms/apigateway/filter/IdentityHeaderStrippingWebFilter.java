package org.pms.apigateway.filter;

import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Removes every client-supplied identity header (all values, any letter case) from every request,
 * public routes included, before anything else can read or forward them. Only {@link
 * IdentityHeadersGatewayFilter} sets them again, from a verified token.
 */
public class IdentityHeaderStrippingWebFilter implements WebFilter, Ordered {

  /** After the correlation filter (HIGHEST_PRECEDENCE), well before Spring Security (-100). */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    List<String> reserved =
        exchange.getRequest().getHeaders().headerNames().stream()
            .filter(IdentityHeaders::isReserved)
            .toList();
    if (reserved.isEmpty()) {
      return chain.filter(exchange);
    }
    ServerHttpRequest stripped =
        exchange
            .getRequest()
            .mutate()
            .headers(headers -> reserved.forEach(headers::remove))
            .build();
    return chain.filter(exchange.mutate().request(stripped).build());
  }

  @Override
  public int getOrder() {
    return ORDER;
  }
}

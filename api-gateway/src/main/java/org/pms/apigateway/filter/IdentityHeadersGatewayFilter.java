package org.pms.apigateway.filter;

import org.pms.apigateway.security.VerifiedIdentity;
import org.pms.apigateway.security.VerifiedIdentityAuthentication;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Sets the identity headers from the verified access token on routes that require one. Applied per
 * route (not globally) so the route table shows exactly which downstreams receive identity. Fails
 * closed: a route using it without an authenticated caller is a configuration error, and the
 * request is not forwarded.
 */
public class IdentityHeadersGatewayFilter implements GatewayFilter {

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    return ReactiveSecurityContextHolder.getContext()
        .map(SecurityContext::getAuthentication)
        .filter(VerifiedIdentityAuthentication.class::isInstance)
        .map(authentication -> ((VerifiedIdentityAuthentication) authentication).getPrincipal())
        .switchIfEmpty(
            Mono.error(
                () ->
                    new IllegalStateException("Identity route reached without a verified caller")))
        .flatMap(identity -> chain.filter(withIdentity(exchange, identity)));
  }

  private static ServerWebExchange withIdentity(
      ServerWebExchange exchange, VerifiedIdentity identity) {
    ServerHttpRequest request =
        exchange
            .getRequest()
            .mutate()
            .headers(
                headers -> {
                  // set() replaces any value, so each header carries exactly one verified value.
                  headers.set(IdentityHeaders.USER_ID, identity.userId().toString());
                  headers.set(IdentityHeaders.USER_ROLES, identity.rolesHeaderValue());
                  if (identity.patientId() != null) {
                    headers.set(IdentityHeaders.PATIENT_ID, identity.patientId().toString());
                  } else {
                    headers.remove(IdentityHeaders.PATIENT_ID);
                  }
                })
            .build();
    return exchange.mutate().request(request).build();
  }
}

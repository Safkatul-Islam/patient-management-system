package org.pms.apigateway.config;

import org.pms.apigateway.filter.IdentityHeaderStrippingWebFilter;
import org.pms.apigateway.filter.IdentityHeadersGatewayFilter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

/**
 * The route table, in Java rather than properties: the downstream URIs come from the validated
 * {@link GatewayProperties}, the path patterns are the same constants the security rules use, and
 * the identity filter is a typed bean. Paths are forwarded unchanged. There are no retries: POSTs
 * are not idempotent, and a retry policy belongs with the Phase 3 resilience work.
 *
 * <p>JWKS and the services' actuator endpoints are deliberately not routed.
 */
@Configuration(proxyBeanMethods = false)
public class RouteConfig {

  // false: no implicit trailing-slash match, so routes match exactly what the security rules do.
  private static final boolean MATCH_TRAILING_SLASH = false;

  @Bean
  IdentityHeaderStrippingWebFilter identityHeaderStrippingWebFilter() {
    return new IdentityHeaderStrippingWebFilter();
  }

  @Bean
  IdentityHeadersGatewayFilter identityHeadersGatewayFilter() {
    return new IdentityHeadersGatewayFilter();
  }

  @Bean
  RouteLocator routes(
      RouteLocatorBuilder builder,
      GatewayProperties properties,
      IdentityHeadersGatewayFilter identityHeaders) {
    return builder
        .routes()
        // Public: no identity headers; auth-service ignores any bearer header here.
        .route(
            "auth-public",
            route ->
                route
                    .method(HttpMethod.POST)
                    .and()
                    .path(MATCH_TRAILING_SLASH, GatewayPaths.AUTH_PUBLIC)
                    .uri(properties.authServiceUri()))
        // Authorization is forwarded: auth-service re-validates its own tokens.
        .route(
            "auth-protected",
            route ->
                route
                    .path(MATCH_TRAILING_SLASH, GatewayPaths.AUTH_PROTECTED)
                    .filters(filters -> filters.filter(identityHeaders))
                    .uri(properties.authServiceUri()))
        // patient-service never receives raw tokens, only the verified identity headers.
        .route(
            "patients",
            route ->
                route
                    .path(MATCH_TRAILING_SLASH, GatewayPaths.PATIENTS)
                    .filters(
                        filters ->
                            filters
                                .removeRequestHeader(HttpHeaders.AUTHORIZATION)
                                .filter(identityHeaders))
                    .uri(properties.patientServiceUri()))
        .build();
  }
}

package org.pms.apigateway.security;

import static org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers.pathMatchers;

import org.pms.apigateway.config.GatewayPaths;
import org.pms.apigateway.error.ProblemResponseWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;

/**
 * Authentication at the trust boundary. The gateway only establishes who the caller is; what the
 * caller may do is decided by each downstream service (gateway authentication does not replace
 * downstream authorization), so no role is checked here.
 *
 * <p>Fail closed: anything not explicitly public or explicitly routed is denied, so an unrouted
 * path answers 401 (no or invalid token) or 403 (valid token) rather than 404, and a route added
 * later without a matching rule here stays locked.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

  /** Reachable without an access token; any bearer header is ignored on these. */
  static final ServerWebExchangeMatcher PUBLIC_ENDPOINTS =
      new OrServerWebExchangeMatcher(
          pathMatchers(HttpMethod.POST, GatewayPaths.AUTH_PUBLIC),
          pathMatchers(GatewayPaths.HEALTH));

  @Bean
  SecurityWebFilterChain securityWebFilterChain(
      ServerHttpSecurity http,
      ReactiveJwtDecoder jwtDecoder,
      IdentityClaimsConverter identityClaimsConverter,
      ProblemServerAuthenticationEntryPoint authenticationEntryPoint,
      ProblemServerAccessDeniedHandler accessDeniedHandler) {
    http
        // CSRF protection defends cookie/session authentication. The gateway keeps no session and
        // accepts credentials only in the Authorization header, which a cross-site form cannot
        // set, so there is nothing for CSRF to protect.
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)
        // Stateless: every request authenticates with its own token; nothing is stored.
        .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
        .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
        .authorizeExchange(
            exchanges ->
                exchanges
                    .matchers(PUBLIC_ENDPOINTS)
                    .permitAll()
                    .pathMatchers(GatewayPaths.AUTH_PROTECTED)
                    .authenticated()
                    .pathMatchers(GatewayPaths.PATIENTS)
                    .authenticated()
                    .anyExchange()
                    .denyAll())
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer
                    .bearerTokenConverter(new HeaderBearerTokenConverter(PUBLIC_ENDPOINTS))
                    .jwt(
                        jwt ->
                            jwt.jwtDecoder(jwtDecoder)
                                .jwtAuthenticationConverter(identityClaimsConverter))
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler));
    return http.build();
  }

  @Bean
  ProblemServerAuthenticationEntryPoint problemServerAuthenticationEntryPoint(
      ProblemResponseWriter writer) {
    return new ProblemServerAuthenticationEntryPoint(writer);
  }

  @Bean
  ProblemServerAccessDeniedHandler problemServerAccessDeniedHandler(ProblemResponseWriter writer) {
    return new ProblemServerAccessDeniedHandler(writer);
  }
}

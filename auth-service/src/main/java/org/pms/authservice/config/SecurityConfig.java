package org.pms.authservice.config;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

import org.pms.common.web.security.ProblemAccessDeniedHandler;
import org.pms.common.web.security.ProblemAuthenticationEntryPoint;
import org.pms.common.web.security.SecurityProblemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stateless bearer-token security. Authorization is URL-based on purpose: failures are then raised
 * in the filter chain and rendered by the problem entry point / access-denied handler, instead of
 * surfacing in controllers where common's catch-all handler would turn them into a 500.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

  /** Reachable without an access token. */
  private static final RequestMatcher PUBLIC_ENDPOINTS =
      new OrRequestMatcher(
          pathPattern(HttpMethod.POST, "/auth/login"),
          pathPattern(HttpMethod.POST, "/auth/refresh"),
          pathPattern(HttpMethod.GET, "/.well-known/jwks.json"),
          pathPattern("/actuator/health/**"),
          pathPattern("/error"));

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      JwtDecoder jwtDecoder,
      JwtAuthenticationConverter jwtAuthenticationConverter,
      ProblemAuthenticationEntryPoint authenticationEntryPoint,
      ProblemAccessDeniedHandler accessDeniedHandler)
      throws Exception {
    http
        // CSRF protection defends cookie/session authentication. This API keeps no session and
        // reads credentials only from the request body or the Authorization header, which a
        // cross-site form cannot set, so there is nothing for CSRF to protect.
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        // Spring Security's own POST /logout filter is unrelated to /auth/logout.
        .logout(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(PUBLIC_ENDPOINTS)
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/auth/logout")
                    .authenticated()
                    .requestMatchers("/auth/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .denyAll())
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer
                    .bearerTokenResolver(ignoringPublicEndpoints())
                    .jwt(
                        jwt ->
                            jwt.decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter))
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler));
    return http.build();
  }

  /**
   * Chosen deliberately rather than inherited: 10 is the library's current default and OWASP's
   * minimum for BCrypt. Revisit upwards if the login latency budget allows.
   */
  static final int BCRYPT_COST = 10;

  /** One encoder for stored hashes and the login dummy hash, so both cost the same to check. */
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(BCRYPT_COST);
  }

  @Bean
  SecurityProblemWriter securityProblemWriter(JsonMapper jsonMapper) {
    return new SecurityProblemWriter(jsonMapper);
  }

  /**
   * RFC 6750: a presented-but-rejected token gets {@code error="invalid_token"}; a missing token
   * gets the bare scheme. The body never says why a token was rejected.
   */
  @Bean
  ProblemAuthenticationEntryPoint problemAuthenticationEntryPoint(
      SecurityProblemWriter problemWriter) {
    return new ProblemAuthenticationEntryPoint(
        problemWriter,
        exception ->
            exception instanceof OAuth2AuthenticationException
                ? "Bearer error=\"invalid_token\""
                : "Bearer",
        "A valid bearer access token is required.");
  }

  @Bean
  ProblemAccessDeniedHandler problemAccessDeniedHandler(SecurityProblemWriter problemWriter) {
    return new ProblemAccessDeniedHandler(problemWriter);
  }

  /**
   * By default any request carrying a bearer header is authenticated, even on public endpoints, so
   * a client refreshing because its access token expired (and still sending it) would get a 401.
   * Public endpoints therefore ignore the header entirely.
   */
  private static BearerTokenResolver ignoringPublicEndpoints() {
    DefaultBearerTokenResolver headerResolver = new DefaultBearerTokenResolver();
    return request -> PUBLIC_ENDPOINTS.matches(request) ? null : headerResolver.resolve(request);
  }
}

package org.pms.patientservice.config;

import static org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.pathPattern;

import java.util.Arrays;
import org.pms.common.web.security.ProblemAccessDeniedHandler;
import org.pms.common.web.security.ProblemAuthenticationEntryPoint;
import org.pms.common.web.security.SecurityProblemWriter;
import org.pms.patientservice.security.GatewayIdentityFilter;
import org.pms.patientservice.security.Role;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stateless security driven by the identity the API gateway forwards in headers. The gateway
 * authenticates; this service still authorizes every request by role here, and by record ownership
 * for a PATIENT in the service layer ("gateway authentication does not replace downstream
 * authorization").
 *
 * <p>Authorization is URL-based on purpose: failures are then raised in the filter chain and
 * rendered by the problem entry point / access-denied handler, instead of surfacing in controllers
 * where common's catch-all handler would turn them into a 500. Anything not listed is denied.
 *
 * <p>Only applies to the servlet web application: a non-web context (e.g. a migration-only startup)
 * has no HTTP surface to secure and no {@link HttpSecurity}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

  private static final String PATIENTS = "/api/v1/patients";
  private static final String PATIENT = "/api/v1/patients/{id}";

  private static final String[] STAFF =
      roleNames(Role.ADMIN, Role.DOCTOR, Role.NURSE, Role.BILLING_STAFF);
  private static final String[] CLINICAL_STAFF = roleNames(Role.ADMIN, Role.DOCTOR, Role.NURSE);
  private static final String[] ANY_ROLE = roleNames(Role.values());

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ProblemAuthenticationEntryPoint authenticationEntryPoint,
      ProblemAccessDeniedHandler accessDeniedHandler)
      throws Exception {
    http
        // CSRF protection defends cookie/session authentication. This service keeps no session
        // and sets no cookies; identity arrives only in headers that the gateway sets after
        // validating the bearer token (and strips from client requests), which a cross-site form
        // cannot forge, so there is nothing for CSRF to protect.
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .addFilterBefore(new GatewayIdentityFilter(), AnonymousAuthenticationFilter.class)
        .authorizeHttpRequests(
            authorize ->
                authorize
                    // Probes carry no identity. /error keeps the real status of a failed request
                    // instead of replacing it with a 401 on the container's error dispatch.
                    .requestMatchers(pathPattern("/actuator/health/**"), pathPattern("/error"))
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, PATIENTS)
                    .hasAnyRole(STAFF)
                    // A PATIENT passes here but is limited to its own record by PatientService.
                    .requestMatchers(HttpMethod.GET, PATIENT)
                    .hasAnyRole(ANY_ROLE)
                    .requestMatchers(HttpMethod.POST, PATIENTS)
                    .hasAnyRole(CLINICAL_STAFF)
                    .requestMatchers(HttpMethod.PUT, PATIENT)
                    .hasAnyRole(CLINICAL_STAFF)
                    .requestMatchers(HttpMethod.DELETE, PATIENT)
                    .hasRole(Role.ADMIN.name())
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler));
    return http.build();
  }

  @Bean
  SecurityProblemWriter securityProblemWriter(JsonMapper jsonMapper) {
    return new SecurityProblemWriter(jsonMapper);
  }

  /** Missing and malformed identities get the same response: the reason is never disclosed. */
  @Bean
  ProblemAuthenticationEntryPoint problemAuthenticationEntryPoint(
      SecurityProblemWriter problemWriter) {
    return new ProblemAuthenticationEntryPoint(
        problemWriter,
        exception -> "Bearer",
        "A verified identity from the API gateway is required.");
  }

  @Bean
  ProblemAccessDeniedHandler problemAccessDeniedHandler(SecurityProblemWriter problemWriter) {
    return new ProblemAccessDeniedHandler(problemWriter);
  }

  private static String[] roleNames(Role... roles) {
    return Arrays.stream(roles).map(Role::name).toArray(String[]::new);
  }
}

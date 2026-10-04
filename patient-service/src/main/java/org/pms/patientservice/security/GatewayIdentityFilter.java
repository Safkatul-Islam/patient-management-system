package org.pms.patientservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.pms.patientservice.security.GatewayIdentityHeaders.MalformedIdentityHeadersException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a request from the identity headers the API gateway forwards (see {@link
 * GatewayIdentityHeaders}). The token is already verified upstream, so there is no credential left
 * to check here: a well-formed set of headers becomes an authenticated {@link
 * PreAuthenticatedAuthenticationToken} whose principal is the {@link GatewayIdentity} and whose
 * only authority is {@code ROLE_<role>}.
 *
 * <p>Missing or malformed headers leave the request unauthenticated; the authorization rules then
 * reject it through the entry point with a 401. This filter never writes a response itself.
 *
 * <p>Must be added to the security filter chain only, never registered as a bean: Spring Boot would
 * also register a bean as a plain servlet filter outside the chain.
 */
public class GatewayIdentityFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(GatewayIdentityFilter.class);
  private static final String ROLE_PREFIX = "ROLE_";
  private static final String NO_CREDENTIALS = "N/A";

  private final SecurityContextHolderStrategy securityContextHolderStrategy =
      SecurityContextHolder.getContextHolderStrategy();

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    try {
      GatewayIdentityHeaders.parse(request).ifPresent(this::authenticate);
    } catch (MalformedIdentityHeadersException ex) {
      // Headers that reach this service malformed mean a gateway bug or a caller bypassing the
      // gateway. The reason names the header only; values are never logged.
      log.warn("Rejected gateway identity headers: {}", ex.getMessage());
    }
    filterChain.doFilter(request, response);
  }

  private void authenticate(GatewayIdentity identity) {
    List<GrantedAuthority> authorities =
        List.of(new SimpleGrantedAuthority(ROLE_PREFIX + identity.role().name()));
    SecurityContext context = securityContextHolderStrategy.createEmptyContext();
    context.setAuthentication(
        new PreAuthenticatedAuthenticationToken(identity, NO_CREDENTIALS, authorities));
    securityContextHolderStrategy.setContext(context);
    log.debug("Authenticated gateway identity for user {}", identity.userId());
  }
}

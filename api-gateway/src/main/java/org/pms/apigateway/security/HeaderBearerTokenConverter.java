package org.pms.apigateway.security;

import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Reads the bearer token from the single {@code Authorization} header only (never the query string
 * or a form body), and not at all on public endpoints.
 *
 * <p>Public endpoints ignore the header entirely: otherwise a client refreshing because its access
 * token expired, and still sending that token, would be rejected with 401 (auth-service had the
 * same issue, see its {@code SecurityConfig.ignoringPublicEndpoints}).
 */
public class HeaderBearerTokenConverter implements ServerAuthenticationConverter {

  private final ServerWebExchangeMatcher publicEndpoints;
  private final ServerBearerTokenAuthenticationConverter delegate;

  public HeaderBearerTokenConverter(ServerWebExchangeMatcher publicEndpoints) {
    this.publicEndpoints = publicEndpoints;
    this.delegate = new ServerBearerTokenAuthenticationConverter();
    this.delegate.setAllowUriQueryParameter(false);
    this.delegate.setAllowFormEncodedBodyParameter(false);
  }

  @Override
  public Mono<Authentication> convert(ServerWebExchange exchange) {
    return publicEndpoints
        .matches(exchange)
        .filter(match -> !match.isMatch())
        .flatMap(notPublic -> fromHeader(exchange));
  }

  private Mono<Authentication> fromHeader(ServerWebExchange exchange) {
    // The delegate reads only the first Authorization header; a second one could carry a
    // different token to whatever the request is forwarded to, so it is rejected outright.
    List<String> values = exchange.getRequest().getHeaders().get(HttpHeaders.AUTHORIZATION);
    if (values != null && values.size() > 1) {
      return Mono.error(
          new OAuth2AuthenticationException(
              BearerTokenErrors.invalidRequest("Multiple Authorization headers")));
    }
    return delegate.convert(exchange);
  }
}

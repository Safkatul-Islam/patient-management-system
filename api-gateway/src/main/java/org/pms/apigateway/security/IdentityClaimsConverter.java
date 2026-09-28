package org.pms.apigateway.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;

/**
 * Turns a verified JWT into the caller's authentication. Malformed identity claims fail here, as an
 * {@code InvalidBearerTokenException}, so they take the same 401 path as a bad signature.
 */
public class IdentityClaimsConverter implements Converter<Jwt, Mono<AbstractAuthenticationToken>> {

  @Override
  public Mono<AbstractAuthenticationToken> convert(Jwt jwt) {
    return Mono.fromCallable(() -> new VerifiedIdentityAuthentication(VerifiedIdentity.from(jwt)));
  }
}

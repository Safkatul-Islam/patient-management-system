package org.pms.apigateway.security;

import java.time.Clock;
import org.pms.apigateway.config.GatewayProperties;
import org.pms.apigateway.security.jwks.BoundedJwkSource;
import org.pms.apigateway.security.jwks.JwksClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtTypeValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/** Access-token verification: RS256 only, keys from the bounded JWKS source, strict claims. */
@Configuration(proxyBeanMethods = false)
public class JwtDecoderConfig {

  /** auth-service always sets {@code typ: JWT}; a missing or different {@code typ} is rejected. */
  private static final String TOKEN_TYPE = "JWT";

  @Bean
  BoundedJwkSource boundedJwkSource(GatewayProperties properties, Clock clock) {
    GatewayProperties.Jwks jwks = properties.jwt().jwks();
    JwksClient client = new JwksClient(jwks);
    return new BoundedJwkSource(client::fetch, clock, jwks.cooldown());
  }

  @Bean
  ReactiveJwtDecoder jwtDecoder(
      BoundedJwkSource jwkSource, GatewayProperties properties, Clock clock) {
    // The algorithm is pinned here, never taken from the token header: alg=none, HS256 (key
    // confusion) and every other algorithm are rejected before any key is looked up.
    NimbusReactiveJwtDecoder decoder =
        NimbusReactiveJwtDecoder.withJwkSource(jwkSource)
            .jwsAlgorithm(SignatureAlgorithm.RS256)
            // typ is checked by the JwtTypeValidator below instead of by Nimbus.
            .validateType(false)
            .build();

    JwtTimestampValidator timestamps = new JwtTimestampValidator();
    timestamps.setClock(clock);
    // By default a token without exp passes; every token auth-service issues has one.
    timestamps.setAllowEmptyExpiryClaim(false);

    GatewayProperties.Jwt jwt = properties.jwt();
    decoder.setJwtValidator(
        JwtValidators.createDefaultWithValidators(
            new JwtTypeValidator(TOKEN_TYPE),
            timestamps,
            new JwtIssuerValidator(jwt.issuer()),
            new JwtAudienceValidator(jwt.audience())));
    return decoder;
  }

  @Bean
  IdentityClaimsConverter identityClaimsConverter() {
    return new IdentityClaimsConverter();
  }
}

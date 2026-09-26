package org.pms.authservice.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * RS256 signing and verification. auth-service verifies its own access tokens in-process with the
 * public half of the signing key (no JWKS round trip to itself).
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

  public static final String ROLES_CLAIM = "roles";
  public static final String PATIENT_ID_CLAIM = "patientId";

  /** The signing key as a JWK, private part included. Only its public view is ever published. */
  @Bean
  RSAKey signingJwk(JwtProperties properties) {
    SigningKeyLoader.RsaKeyPair keyPair = SigningKeyLoader.load(properties.privateKey());
    return new RSAKey.Builder(keyPair.publicKey())
        .privateKey(keyPair.privateKey())
        .keyID(properties.keyId())
        .keyUse(KeyUse.SIGNATURE)
        .algorithm(JWSAlgorithm.RS256)
        .build();
  }

  @Bean
  JwtEncoder jwtEncoder(RSAKey signingJwk) {
    return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingJwk)));
  }

  @Bean
  JwtDecoder jwtDecoder(RSAKey signingJwk, JwtProperties properties, Clock clock) throws Exception {
    // Pinning RS256 and a single public key rejects alg=none, HMAC algorithm confusion and
    // tokens signed by any other key, whatever their kid header says.
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withPublicKey(signingJwk.toRSAPublicKey())
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();
    JwtTimestampValidator timestamps = new JwtTimestampValidator();
    timestamps.setClock(clock);
    OAuth2TokenValidator<Jwt> validator =
        JwtValidators.createDefaultWithValidators(
            timestamps,
            new JwtIssuerValidator(properties.issuer()),
            new JwtAudienceValidator(properties.audience()));
    decoder.setJwtValidator(validator);
    return decoder;
  }

  /** Maps {@code roles: ["ADMIN"]} to the authority {@code ROLE_ADMIN}. */
  @Bean
  JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName(ROLES_CLAIM);
    authorities.setAuthorityPrefix("ROLE_");
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    return converter;
  }
}

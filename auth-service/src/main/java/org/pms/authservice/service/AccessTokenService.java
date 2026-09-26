package org.pms.authservice.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.pms.authservice.config.JwtConfig;
import org.pms.authservice.config.JwtProperties;
import org.pms.authservice.model.Role;
import org.pms.authservice.model.User;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues RS256 access tokens. Claims carry identity and authorization only (subject, role, patient
 * link) and no PII such as the email.
 */
@Service
public class AccessTokenService {

  private final JwtEncoder jwtEncoder;
  private final JwtProperties properties;
  private final Clock clock;

  public AccessTokenService(JwtEncoder jwtEncoder, JwtProperties properties, Clock clock) {
    this.jwtEncoder = jwtEncoder;
    this.properties = properties;
    this.clock = clock;
  }

  public String issue(User user) {
    // JWT times have second precision; truncating keeps exp - iat exactly the configured TTL.
    Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    JwtClaimsSet.Builder claims =
        JwtClaimsSet.builder()
            .issuer(properties.issuer())
            .subject(user.getId().toString())
            .audience(List.of(properties.audience()))
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plus(properties.accessTokenTtl()))
            .id(UUID.randomUUID().toString())
            .claim(JwtConfig.ROLES_CLAIM, List.of(user.getRole().name()));
    if (user.getRole() == Role.PATIENT) {
      claims.claim(JwtConfig.PATIENT_ID_CLAIM, user.getPatientId().toString());
    }
    JwsHeader header =
        JwsHeader.with(SignatureAlgorithm.RS256).keyId(properties.keyId()).type("JWT").build();
    return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
  }

  public long ttlSeconds() {
    return properties.accessTokenTtl().toSeconds();
  }
}

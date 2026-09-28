package org.pms.apigateway.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * An authenticated caller. Holds the verified identity only; the raw token is not retained. The
 * role authorities are informational: the gateway authenticates, each service authorizes.
 */
public class VerifiedIdentityAuthentication extends AbstractAuthenticationToken {

  private final VerifiedIdentity identity;

  public VerifiedIdentityAuthentication(VerifiedIdentity identity) {
    super(
        identity.roles().stream()
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
            .toList());
    this.identity = identity;
    setAuthenticated(true);
  }

  @Override
  public VerifiedIdentity getPrincipal() {
    return identity;
  }

  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public String getName() {
    return identity.userId().toString();
  }
}

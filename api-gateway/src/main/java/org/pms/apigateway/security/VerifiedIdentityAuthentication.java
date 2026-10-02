package org.pms.apigateway.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * An authenticated caller. Holds the verified identity only; the raw token is not retained. The
 * role authorities are informational: the gateway authenticates, each service authorizes.
 */
public class VerifiedIdentityAuthentication extends AbstractAuthenticationToken {

  private final VerifiedIdentity identity;

  public VerifiedIdentityAuthentication(VerifiedIdentity identity) {
    super(List.of(new SimpleGrantedAuthority("ROLE_" + identity.role().name())));
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

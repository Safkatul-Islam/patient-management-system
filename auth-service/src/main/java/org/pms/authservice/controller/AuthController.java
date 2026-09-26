package org.pms.authservice.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import org.pms.authservice.dto.LoginRequest;
import org.pms.authservice.dto.RefreshTokenRequest;
import org.pms.authservice.dto.TokenResponse;
import org.pms.authservice.service.AuthService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/login")
  public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
    return tokens(authService.login(request.email(), request.password()));
  }

  @PostMapping("/refresh")
  public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
    return tokens(authService.refresh(request.refreshToken()));
  }

  /** Always 204: whether the token existed or belonged to the caller is not disclosed. */
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RefreshTokenRequest request) {
    authService.logout(UUID.fromString(jwt.getSubject()), request.refreshToken());
    return ResponseEntity.noContent().build();
  }

  /** Token responses must never be cached (RFC 6749 section 5.1). */
  private static ResponseEntity<TokenResponse> tokens(TokenResponse body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}

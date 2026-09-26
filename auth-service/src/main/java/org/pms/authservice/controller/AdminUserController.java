package org.pms.authservice.controller;

import jakarta.validation.Valid;
import org.pms.authservice.dto.CreatePatientAccountRequest;
import org.pms.authservice.dto.CreateStaffUserRequest;
import org.pms.authservice.dto.UserResponse;
import org.pms.authservice.service.UserAdminService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only account creation (enforced in SecurityConfig for /auth/admin/**). */
@RestController
@RequestMapping("/auth/admin")
public class AdminUserController {

  private final UserAdminService userAdminService;

  public AdminUserController(UserAdminService userAdminService) {
    this.userAdminService = userAdminService;
  }

  @PostMapping("/users")
  public ResponseEntity<UserResponse> createStaffUser(
      @Valid @RequestBody CreateStaffUserRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(userAdminService.createStaffUser(request));
  }

  @PostMapping("/patients")
  public ResponseEntity<UserResponse> createPatientAccount(
      @Valid @RequestBody CreatePatientAccountRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(userAdminService.createPatientAccount(request));
  }
}

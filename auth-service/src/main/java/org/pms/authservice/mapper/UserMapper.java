package org.pms.authservice.mapper;

import org.pms.authservice.dto.UserResponse;
import org.pms.authservice.model.User;

public final class UserMapper {

  private UserMapper() {}

  public static UserResponse toResponse(User user) {
    return new UserResponse(
        user.getId(),
        user.getEmail(),
        user.getRole(),
        user.getPatientId(),
        user.isActive(),
        user.getCreatedAt());
  }
}

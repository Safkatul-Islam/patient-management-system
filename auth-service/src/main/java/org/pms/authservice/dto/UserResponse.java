package org.pms.authservice.dto;

import java.time.Instant;
import java.util.UUID;
import org.pms.authservice.model.Role;

/** Account view returned to ADMIN callers. Never carries the password hash. */
public record UserResponse(
    UUID id, String email, Role role, UUID patientId, boolean active, Instant createdAt) {}

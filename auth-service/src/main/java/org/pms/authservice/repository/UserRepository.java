package org.pms.authservice.repository;

import java.util.Optional;
import java.util.UUID;
import org.pms.authservice.model.Role;
import org.pms.authservice.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

  /** Emails are stored normalized (trimmed, lower case); callers must normalize first. */
  Optional<User> findByEmail(String email);

  boolean existsByEmail(String email);

  boolean existsByPatientId(UUID patientId);

  boolean existsByRole(Role role);
}

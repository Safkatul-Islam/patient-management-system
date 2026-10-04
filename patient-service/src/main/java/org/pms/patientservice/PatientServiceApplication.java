package org.pms.patientservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Callers are authenticated from gateway identity headers only, so Boot's default in-memory user
 * (whose generated password it would log at startup) is excluded.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class PatientServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(PatientServiceApplication.class, args);
  }
}

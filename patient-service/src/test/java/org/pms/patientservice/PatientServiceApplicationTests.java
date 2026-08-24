package org.pms.patientservice;

import org.junit.jupiter.api.Test;
import org.pms.patientservice.support.PostgresTestcontainerConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(PostgresTestcontainerConfig.class)
class PatientServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}

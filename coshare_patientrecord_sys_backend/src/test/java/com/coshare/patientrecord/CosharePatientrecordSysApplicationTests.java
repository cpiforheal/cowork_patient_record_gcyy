package com.coshare.patientrecord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MYSQL_URL", matches = ".+")
class CosharePatientrecordSysApplicationTests {

    @Test
    void contextLoads() {
    }

}

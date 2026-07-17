package com.coshare.patientrecord.config;

import jakarta.annotation.PostConstruct;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class ProductionConfigurationValidator {

    private final String datasourceUrl;
    private final String runtimeUser;
    private final String migrationUser;

    public ProductionConfigurationValidator(
        @Value("${spring.datasource.url}") String datasourceUrl,
        @Value("${spring.datasource.username}") String runtimeUser,
        @Value("${spring.flyway.user}") String migrationUser
    ) {
        this.datasourceUrl = datasourceUrl == null ? "" : datasourceUrl;
        this.runtimeUser = runtimeUser == null ? "" : runtimeUser;
        this.migrationUser = migrationUser == null ? "" : migrationUser;
    }

    @PostConstruct
    public void validate() {
        String normalizedUrl = datasourceUrl.toLowerCase(Locale.ROOT);
        if (!normalizedUrl.contains("sslmode=verify_identity") && !normalizedUrl.contains("sslmode=verify_ca")) {
            throw new IllegalStateException("Production MYSQL_URL must enforce sslMode=VERIFY_IDENTITY or VERIFY_CA");
        }
        if (runtimeUser.isBlank() || migrationUser.isBlank()) {
            throw new IllegalStateException("Production runtime and migration database accounts are required");
        }
        if (runtimeUser.equalsIgnoreCase(migrationUser)) {
            throw new IllegalStateException("Production migration and runtime database accounts must be different");
        }
    }
}

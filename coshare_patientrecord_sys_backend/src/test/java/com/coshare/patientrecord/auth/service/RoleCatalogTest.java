package com.coshare.patientrecord.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RoleCatalogTest {

    @Test
    void exposesExactlyTheCanonicalPostsInWorkflowOrder() {
        assertThat(RoleCatalog.definitions().stream().map(RoleCatalog.RoleDefinition::role)).containsExactly(
            "admin", "manager", "quality", "display", "frontdesk", "reception", "inspection", "tcm",
            "doctor", "nurse", "lab", "ecg", "ultrasound", "warehouse", "tcm_pharmacy"
        );
    }

    @Test
    void legacyAliasesConvergeWithoutBecomingNewRoles() {
        assertThat(RoleCatalog.canonicalize("nursing")).isEqualTo("nurse");
        assertThat(RoleCatalog.canonicalize("tcmPharmacyOperator")).isEqualTo("tcm_pharmacy");
        assertThat(RoleCatalog.canonicalize("pharmacist")).isEqualTo("tcm_pharmacy");
        assertThat(RoleCatalog.canonicalize("pharmacy")).isEqualTo("tcm_pharmacy");
        assertThat(RoleCatalog.canonicalize("decoction")).isEqualTo("tcm_pharmacy");
        assertThat(RoleCatalog.isCanonical("nursing")).isFalse();
        assertThat(RoleCatalog.roles()).hasSize(15);
    }
}

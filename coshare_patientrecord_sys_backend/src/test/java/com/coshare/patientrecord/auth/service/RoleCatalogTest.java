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
        assertThat(RoleCatalog.canonicalize("前台")).isEqualTo("frontdesk");
        assertThat(RoleCatalog.canonicalize("登记前台")).isEqualTo("frontdesk");
        assertThat(RoleCatalog.canonicalize("接诊医生")).isEqualTo("reception");
        assertThat(RoleCatalog.canonicalize("检查岗位")).isEqualTo("inspection");
        assertThat(RoleCatalog.canonicalize("医生岗位")).isEqualTo("doctor");
        assertThat(RoleCatalog.canonicalize("护理")).isEqualTo("nurse");
        assertThat(RoleCatalog.canonicalize("中药房")).isEqualTo("tcm_pharmacy");
        assertThat(RoleCatalog.isCanonical("nursing")).isFalse();
        assertThat(RoleCatalog.roles()).hasSize(15);
    }
}

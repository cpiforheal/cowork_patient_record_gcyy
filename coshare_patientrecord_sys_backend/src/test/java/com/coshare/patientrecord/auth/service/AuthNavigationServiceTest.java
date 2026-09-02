package com.coshare.patientrecord.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.coshare.patientrecord.auth.dto.NavigationMenu;
import com.coshare.patientrecord.auth.dto.NavigationResult;
import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.config.PortalMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AuthNavigationServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AuthNavigationService service;

    @BeforeEach
    void setUp() {
        service = new AuthNavigationService(jdbcTemplate);
    }

    @Test
    void doctorSideAndAdminRolesCanEditEveryClinicalStage() {
        for (String role : List.of("admin", "doctor", "lab", "ecg", "ultrasound", "inspection", "reception", "tcm", "nurse", "nursing")) {
            for (String stage : List.of("REGISTRATION", "INSPECTION", "RECEPTION", "TCM", "DOCTOR", "SURGERY", "REVIEW")) {
                assertThat(service.canEditStage(role, stage)).as(role + "/" + stage).isTrue();
            }
        }
        assertThat(service.canEditStage("frontdesk", "REGISTRATION")).isTrue();
        assertThat(service.canEditStage("frontdesk", "DOCTOR")).isFalse();
        assertThat(service.canCorrectStage("doctor", "REVIEW")).isFalse();
    }

    @Test
    void doctorSideAndAdminRolesCanEditEveryAuxiliaryTask() {
        assertThat(service.canEditAuxiliary("lab", "LAB")).isTrue();
        assertThat(service.canEditAuxiliary("doctor", "LAB")).isTrue();
        assertThat(service.canEditAuxiliary("admin", "ECG")).isTrue();
        assertThat(service.canEditAuxiliary("ecg", "LAB")).isTrue();
        assertThat(service.canEditAuxiliary("inspection", "COLONOSCOPY")).isTrue();
        assertThat(service.canEditAuxiliary("ultrasound", "IMAGING")).isTrue();
        assertThat(service.canEditAuxiliary("frontdesk", "IMAGING")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void navigationPublishesCapabilitiesFromTheFixedPolicy() {
        doReturn(List.of()).when(jdbcTemplate).query(anyString(), any(RowMapper.class), anyString());
        NavigationResult doctor = service.navigationFor(user("doctor"));
        assertThat(doctor.policyVersion()).isEqualTo(AuthNavigationService.POLICY_VERSION);
        assertThat(doctor.capabilities()).contains(
            "preai:encounter:create",
            "preai:review",
            "preai:duties:manage",
            "preai:stage:registration:edit",
            "preai:stage:doctor:edit",
            "preai:stage:doctor:correct",
            "preai:auxiliary:lab:edit",
            "preai:auxiliary:lab:create"
        );
        assertThat(service.hasCapability(user("doctor"), "preai:review")).isTrue();
        assertThat(service.hasCapability(user("doctor"), "preai:stage:doctor:edit")).isTrue();

        NavigationResult quality = service.navigationFor(user("quality"));
        assertThat(quality.capabilities()).doesNotContain("user:create", "preai:review");
        assertThat(quality.buttonPermissions()).doesNotContainKey("accountManage");

        SessionUser manager = user("manager");
        assertThat(service.hasCapability(manager, "inventory:read")).isTrue();
        assertThat(service.hasCapability(manager, "inventory:export")).isTrue();
        assertThat(service.hasCapability(manager, "inventory:issue")).isFalse();
        assertThat(service.hasCapability(manager, "inventory:item:manage")).isFalse();
        assertThat(service.hasCapability(manager, "inventory:count")).isFalse();
        assertThat(service.hasCapability(manager, "inventory:receive")).isFalse();

        SessionUser qualityUser = user("quality");
        assertThat(service.hasCapability(qualityUser, "inventory:receive")).isFalse();
        assertThat(service.hasCapability(qualityUser, "inventory:approve")).isFalse();
        assertThat(service.hasCapability(qualityUser, "inventory:rule")).isTrue();
        assertThat(service.hasCapability(qualityUser, "inventory:confirm")).isTrue();
        assertThat(service.hasCapability(qualityUser, "inventory:retry")).isTrue();
        assertThat(service.hasCapability(qualityUser, "inventory:item:manage")).isFalse();

        SessionUser warehouse = user("warehouse");
        assertThat(service.hasCapability(warehouse, "inventory:receive")).isTrue();
        assertThat(service.hasCapability(warehouse, "inventory:issue")).isTrue();
        assertThat(service.hasCapability(warehouse, "inventory:item:manage")).isTrue();
        assertThat(service.hasCapability(warehouse, "inventory:retry")).isTrue();
        assertThat(service.hasCapability(warehouse, "inventory:rule")).isFalse();
        assertThat(service.hasCapability(warehouse, "inventory:confirm")).isFalse();

        NavigationResult admin = service.navigationFor(user("admin"));
        assertThat(admin.capabilities()).doesNotContain("inventory:issue");
        assertThat(admin.capabilities()).contains(
            "maintenance:purge", "maintenance:backup", "inventory:item:manage", "preai:encounter:create",
            "preai:review", "preai:stage:doctor:edit", "preai:auxiliary:lab:edit"
        );
        assertThat(service.hasCapability(user("admin"), "inventory:item:manage")).isTrue();
        assertThat(service.hasCapability(user("admin"), "inventory:issue")).isFalse();
        assertThat(service.hasCapability(user("admin"), "preai:encounter:create")).isTrue();
        assertThat(findMenu(admin.menus(), "/system/dataMaintenance").meta().title()).isEqualTo("数据维护");
        assertThat(findMenuOrNull(service.navigationFor(user("quality")).menus(), "/system/dataMaintenance")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void warehouseNavigationExposesItsTaskEntriesAndKeepsCompatibilityRouteHidden() {
        doReturn(List.of()).when(jdbcTemplate).query(anyString(), any(RowMapper.class), anyString());

        NavigationMenu inventory = findMenu(service.navigationFor(user("warehouse")).menus(), "/inventory");
        assertThat(inventory.children()).isNotNull();
        assertThat(inventory.children().stream().filter(item -> !item.meta().isHide()).map(NavigationMenu::path))
            .containsExactly(
                "/inventory/overview",
                "/inventory/requests",
                "/inventory/stock",
                "/inventory/controls",
                "/inventory/packages",
                "/inventory/weekly",
                "/inventory/trace",
                "/inventory/items"
            );
        assertThat(inventory.children().stream().filter(item -> !item.meta().isHide()).map(item -> item.meta().title()))
            .containsExactly("今日待办", "申领与签收", "入库与库存", "盘点与报损", "患者耗材套餐", "周用量核对", "出入库记录", "物资设置");
        assertThat(inventory.children().stream().filter(item -> !item.meta().isHide()).map(item -> item.meta().icon()))
            .containsExactly("Monitor", "Tickets", "Box", "SetUp", "CollectionTag", "DataLine", "Search", "Goods");
        assertThat(inventory.children().stream().filter(item -> !item.meta().isHide()).map(item -> item.meta().activeMenu()))
            .containsOnlyNulls();

        NavigationMenu compatibility = findMenu(inventory.children(), "/inventory/manage");
        assertThat(compatibility.meta().isHide()).isTrue();
        assertThat(compatibility.redirect()).isEqualTo("/inventory/overview");
    }

    @Test
    @SuppressWarnings("unchecked")
    void inventoryNavigationFiltersEachTaskByItsOriginalRolePermissions() {
        doReturn(List.of()).when(jdbcTemplate).query(anyString(), any(RowMapper.class), anyString());

        NavigationMenu staffInventory = findMenu(service.navigationFor(user("doctor")).menus(), "/inventory");
        assertThat(staffInventory.children().stream().filter(item -> !item.meta().isHide()).map(NavigationMenu::path))
            .containsExactly("/inventory/overview", "/inventory/requests", "/inventory/controls", "/inventory/packages", "/inventory/weekly");
        assertThat(findMenu(staffInventory.children(), "/inventory/manage").meta().isHide()).isTrue();

        NavigationMenu managerInventory = findMenu(service.navigationFor(user("manager")).menus(), "/inventory");
        assertThat(managerInventory.children().stream().filter(item -> !item.meta().isHide()).map(NavigationMenu::path))
            .containsExactly("/inventory/overview", "/inventory/executive");

        assertThat(findMenuOrNull(service.navigationFor(user("reception")).menus(), "/inventory")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void inventoryCompatibilityRouteRedirectsToTheFirstAuthorizedTask() {
        doReturn(List.of()).when(jdbcTemplate).query(anyString(), any(RowMapper.class), anyString());

        List<NavigationMenu> weeklyOnlyMenus = service.filterMenus(
            service.navigationFor(user("admin")).menus(),
            Set.of("/inventory/weekly", "/inventory/manage")
        );
        NavigationMenu inventory = findMenu(weeklyOnlyMenus, "/inventory");

        assertThat(inventory.redirect()).isEqualTo("/inventory/weekly");
        assertThat(findMenu(inventory.children(), "/inventory/manage").redirect()).isEqualTo("/inventory/weekly");
    }

    @Test
    @SuppressWarnings("unchecked")
    void businessWorkbenchUsesRegisteredTaskIcons() {
        doReturn(List.of()).when(jdbcTemplate).query(anyString(), any(RowMapper.class), anyString());

        List<NavigationMenu> menus = service.navigationFor(user("admin")).menus();
        assertThat(findMenu(menus, "/navigation/business-workbench").meta().icon()).isEqualTo("Operation");
        assertThat(findMenu(menus, "/tcm-pharmacy/workbench").meta().icon()).isEqualTo("FirstAidKit");
    }

    @Test
    @SuppressWarnings("unchecked")
    void patientDocumentAndAuditNavigationUsesTaskLanguageAndStableRoutes() {
        doReturn(List.of()).when(jdbcTemplate).query(anyString(), any(RowMapper.class), anyString());

        List<NavigationMenu> menus = service.navigationFor(user("admin")).menus();
        NavigationMenu patient = findMenu(menus, "/navigation/patient-collaboration");
        assertThat(patient.meta().title()).isEqualTo("患者就诊");
        assertThat(patient.meta().icon()).isEqualTo("UserFilled");
        assertThat(patient.redirect()).isEqualTo("/pre-ai/encounters");
        assertThat(visibleChildren(patient).stream().map(NavigationMenu::path)).containsExactly(
            "/pre-ai/encounters", "/patients/overview", "/patients/list", "/workbench/upload", "/workbench/lab-report"
        );
        assertThat(visibleChildren(patient).stream().map(item -> item.meta().title())).containsExactly(
            "登记与事实采集", "患者概览", "患者档案查询", "患者资料上传", "检验报告填写"
        );
        assertThat(visibleChildren(patient).stream().map(item -> item.meta().icon())).containsExactly(
            "EditPen", "Connection", "Search", "UploadFilled", "Memo"
        );
        NavigationMenu detail = findMenu(patient.children(), "/patients/detail/:id");
        assertThat(detail.meta().isHide()).isTrue();
        assertThat(detail.meta().activeMenu()).isEqualTo("/patients/list");

        assertThat(findMenuOrNull(menus, "/navigation/materials-documents")).isNull();
        assertThat(findMenuOrNull(menus, "/templates/ai-document")).isNull();
        assertThat(findMenuOrNull(menus, "/system/aiAssistantAnalysis")).isNull();
        assertThat(findMenuOrNull(menus, "/system/menuMange")).isNotNull();
        assertThat(findMenu(menus, "/system/menuMange").redirect()).isEqualTo("/system/roleManage");
        assertThat(findMenu(menus, "/system/menuMange").meta().isHide()).isTrue();

        NavigationMenu quality = findMenu(menus, "/navigation/quality-audit");
        assertThat(quality.meta().title()).isEqualTo("审核与追溯");
        assertThat(quality.meta().icon()).isEqualTo("DocumentChecked");
        assertThat(visibleChildren(quality).stream().map(item -> item.meta().title())).containsExactly(
            "待审病历", "作废资料恢复", "操作记录查询"
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void taskGroupsRedirectToTheFirstAuthorizedEntryWithoutExpandingPermissions() {
        doReturn(List.of()).when(jdbcTemplate).query(anyString(), any(RowMapper.class), anyString());

        NavigationResult doctor = service.navigationFor(user("doctor"));
        assertThat(findMenu(doctor.menus(), "/navigation/patient-collaboration").redirect()).isEqualTo("/pre-ai/encounters");
        assertThat(findMenuOrNull(doctor.menus(), "/navigation/materials-documents")).isNull();
        assertThat(findMenu(doctor.menus(), "/workbench").redirect()).isEqualTo("/workbench/lab-report");

        NavigationResult frontdesk = service.navigationFor(user("frontdesk"));
        assertThat(findMenu(frontdesk.menus(), "/workbench").redirect()).isEqualTo("/workbench/upload");

        NavigationResult quality = service.navigationFor(user("quality"));
        NavigationMenu qualityPatient = findMenu(quality.menus(), "/navigation/patient-collaboration");
        assertThat(qualityPatient.redirect()).isEqualTo("/patients/overview");
        assertThat(findMenuOrNull(qualityPatient.children(), "/pre-ai/encounters")).isNull();
        assertThat(findMenu(quality.menus(), "/navigation/quality-audit").redirect()).isEqualTo("/audit/review");

        NavigationResult manager = service.navigationFor(user("manager"));
        assertThat(findMenuOrNull(manager.menus(), "/navigation/materials-documents")).isNull();
        assertThat(findMenuOrNull(manager.menus(), "/navigation/patient-collaboration")).isNull();
        assertThat(findMenuOrNull(manager.menus(), "/navigation/quality-audit")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shortcutsPrioritizeEachRolesPrimaryTask() {
        doReturn(List.of()).when(jdbcTemplate).query(anyString(), any(RowMapper.class), anyString());

        assertThat(service.navigationFor(user("doctor")).shortcuts().get(0).path()).isEqualTo("/pre-ai/encounters");
        assertThat(service.navigationFor(user("quality")).shortcuts().get(0).path()).isEqualTo("/audit/review");
        assertThat(service.navigationFor(user("manager")).shortcuts().get(0).path()).isEqualTo("/inventory/overview");
        assertThat(service.navigationFor(user("manager")).shortcuts())
            .noneMatch(item -> "/templates/ai-document".equals(item.path()));
        assertThat(service.navigationFor(user("quality")).shortcuts())
            .noneMatch(item -> "/pre-ai/encounters".equals(item.path()));
    }

    @Test
    void inventoryPortalPublishesOnlyTheDailyReportNavigation() {
        InventoryAccessService accessService = mock(InventoryAccessService.class);
        InventoryAccessService.Profile profile = new InventoryAccessService.Profile(
            InventoryAccessService.DEPARTMENT_REPORTER,
            "reporter",
            "daily report",
            false,
            Set.of("/inventory/daily"),
            Map.of("inventoryDaily", List.of("inventory:read")),
            Set.of("inventory:read"),
            List.of("read")
        );
        doReturn(new InventoryAccessService.Access(profile)).when(accessService).accessFor(any());
        AuthNavigationService inventoryPortal = new AuthNavigationService(jdbcTemplate, accessService, new PortalMode("inventory"));

        NavigationResult navigation = inventoryPortal.navigationFor(user("inventory_reporter"));

        assertThat(navigation.menus()).hasSize(1);
        assertThat(navigation.menus().get(0).path()).isEqualTo("/inventory");
        assertThat(navigation.menus().get(0).children()).extracting(NavigationMenu::path).containsExactly("/inventory/daily");
        assertThat(navigation.capabilities()).containsExactly("inventory:read");
        assertThat(navigation.buttonPermissions()).containsOnlyKeys("inventoryDaily");
        assertThat(navigation.departments()).hasSize(1);
        assertThat(navigation.departments().get(0).id()).isEqualTo("dept-1");
    }

    @Test
    void unknownRoleIsDeniedInsteadOfFallingBackToFrontDesk() {
        assertThatThrownBy(() -> service.navigationFor(user("unknown-role")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("角色未配置");
    }

    private NavigationMenu findMenu(List<NavigationMenu> menus, String path) {
        NavigationMenu result = findMenuOrNull(menus, path);
        if (result != null) return result;
        throw new AssertionError("Menu not found: " + path);
    }

    private NavigationMenu findMenuOrNull(List<NavigationMenu> menus, String path) {
        for (NavigationMenu item : menus) {
            if (path.equals(item.path())) return item;
            if (item.children() != null && !item.children().isEmpty()) {
                NavigationMenu nested = findMenuOrNull(item.children(), path);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private List<NavigationMenu> visibleChildren(NavigationMenu menu) {
        return menu.children().stream().filter(item -> !item.meta().isHide()).toList();
    }

    private SessionUser user(String role) {
        return new SessionUser("account-1", role, role, role, role, "dept-1", "门诊", false, Instant.now().plusSeconds(3600));
    }
}

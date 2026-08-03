package com.coshare.patientrecord.auth.service;

import com.coshare.patientrecord.auth.dto.AuxiliaryPermission;
import com.coshare.patientrecord.auth.dto.DepartmentOption;
import com.coshare.patientrecord.auth.dto.NavigationMenu;
import com.coshare.patientrecord.auth.dto.NavigationMeta;
import com.coshare.patientrecord.auth.dto.NavigationResult;
import com.coshare.patientrecord.auth.dto.NavigationShortcut;
import com.coshare.patientrecord.auth.dto.SessionUser;
import com.coshare.patientrecord.auth.dto.StagePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class AuthNavigationService {

    public static final String VERSION = "2026.08.03.1";
    public static final String POLICY_VERSION = VERSION;
    private static final Logger log = LoggerFactory.getLogger(AuthNavigationService.class);
    private static final List<String> STAGES = List.of(
        "REGISTRATION", "INSPECTION", "RECEPTION", "TCM", "DOCTOR", "SURGERY", "REVIEW"
    );
    private static final Map<String, Set<String>> STAGE_EDITORS = Map.of(
        "REGISTRATION", Set.of("frontdesk"),
        "INSPECTION", Set.of("inspection"),
        "RECEPTION", Set.of("reception"),
        "TCM", Set.of("tcm"),
        "DOCTOR", Set.of("doctor"),
        "SURGERY", Set.of("nurse"),
        "REVIEW", Set.of("doctor")
    );
    private static final Map<String, Set<String>> AUXILIARY_EDITORS = Map.of(
        "LAB", Set.of("lab"),
        "ECG", Set.of("ecg"),
        "IMAGING", Set.of("ultrasound"),
        "VITAL_SIGNS", Set.of("nurse"),
        "COLONOSCOPY", Set.of("inspection")
    );
    private static final Set<String> PRE_AI_FULL_OPERATOR_ROLES = Set.of(
        "admin", "reception", "inspection", "tcm", "doctor", "nurse", "lab", "ecg", "ultrasound"
    );
    private static final Map<String, Set<String>> PRE_AI_CAPABILITY_ROLES = Map.of(
        "preai:encounter:create", setWith(PRE_AI_FULL_OPERATOR_ROLES, "frontdesk"),
        "preai:legacy:import", Set.of("frontdesk"),
        "preai:review", PRE_AI_FULL_OPERATOR_ROLES,
        "preai:duties:manage", Set.of("admin", "doctor"),
        "preai:surgery:confirm", PRE_AI_FULL_OPERATOR_ROLES
    );
    private final JdbcTemplate jdbcTemplate;
    private final InventoryAccessService inventoryAccessService;
    private final List<NavigationMenu> menus = buildMenus();
    private final Map<String, RolePolicy> policies = buildPolicies();
    private final List<NavigationShortcut> shortcuts = List.of(
        shortcut("登记与事实采集", "登记患者并完成前置事实采集", "EditPen", "/pre-ai/encounters"),
        shortcut("患者进度", "查看患者当前诊疗阶段", "Connection", "/encounters/active"),
        shortcut("患者档案查询", "按姓名和门诊号查询历史档案", "Search", "/patients/list"),
        shortcut("患者资料上传", "定位患者后上传本科室资料", "UploadFilled", "/workbench/upload"),
        shortcut("检验报告填写", "填写并复核患者检验报告", "Memo", "/workbench/lab-report"),
        shortcut("通用文书生成", "生成并下载独立 Word 文稿", "DocumentAdd", "/templates/ai-document"),
        shortcut("进销存管理", "科室申领、库存与自动扣减", "Box", "/inventory/overview"),
        shortcut("中药房工作台", "收费、审方、调剂和取药", "FirstAidKit", "/tcm-pharmacy/workbench"),
        shortcut("检查接诊叫号", "管理检查与接诊双队列", "Guide", "/tcm-pharmacy/clinic-queue/workbench"),
        shortcut("待审病历", "退回整改或通过归档", "Tickets", "/audit/review"),
        shortcut("作废资料恢复", "恢复误作废的患者资料", "RefreshLeft", "/documents/recycle"),
        shortcut("操作记录查询", "追踪关键资料改动", "Search", "/audit/log")
    );

    public AuthNavigationService(JdbcTemplate jdbcTemplate, InventoryAccessService inventoryAccessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.inventoryAccessService = inventoryAccessService;
    }

    public NavigationResult navigationFor(SessionUser user) {
        RolePolicy basePolicy = policies.get(normalizeRole(user.role()));
        if (basePolicy == null) {
            log.warn(
                "SECURITY_AUDIT navigation denied for unknown role: userId={}, username={}, role={}",
                user.id(),
                user.username(),
                user.role()
            );
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号角色未配置导航权限，请联系系统管理员");
        }
        RolePolicy policy = effectivePolicy(user, basePolicy);

        List<NavigationMenu> authorizedMenus = filterMenus(menus, effectiveMenuPaths(policy.menuPaths()));
        List<NavigationShortcut> authorizedShortcuts = prioritizeShortcuts(normalizeRole(user.role()), shortcuts.stream()
            .filter(item -> policy.allMenus() || policy.menuPaths().contains(item.path()))
            .toList());
        List<DepartmentOption> departments = authorizedDepartments(user.id());
        DepartmentOption activeDepartment = departments.stream()
            .filter(item -> item.id().equals(user.activeDepartmentId()))
            .findFirst()
            .orElse(null);
        Map<String, StagePermission> stagePermissions = stagePermissions(user.role());
        Map<String, AuxiliaryPermission> auxiliaryPermissions = auxiliaryPermissions(user.role());
        Set<String> capabilities = new LinkedHashSet<>();
        policy.buttonPermissions().values().forEach(capabilities::addAll);
        PRE_AI_CAPABILITY_ROLES.forEach((capability, roles) -> {
            if (roles.contains(normalizeRole(user.role()))) capabilities.add(capability);
        });
        stagePermissions.forEach((stage, permission) -> {
            if (permission.editable()) capabilities.add("preai:stage:" + stage.toLowerCase(Locale.ROOT) + ":edit");
            if (permission.correctable()) capabilities.add("preai:stage:" + stage.toLowerCase(Locale.ROOT) + ":correct");
        });
        auxiliaryPermissions.forEach((task, permission) -> {
            if (permission.editable()) capabilities.add("preai:auxiliary:" + task.toLowerCase(Locale.ROOT) + ":edit");
            if (canCreateAuxiliary(user.role(), task)) {
                capabilities.add("preai:auxiliary:" + task.toLowerCase(Locale.ROOT) + ":create");
            }
        });
        return new NavigationResult(
            VERSION,
            POLICY_VERSION,
            authorizedMenus,
            policy.buttonPermissions(),
            authorizedShortcuts,
            activeDepartment,
            departments,
            List.copyOf(capabilities),
            stagePermissions,
            auxiliaryPermissions
        );
    }

    public boolean canEditStage(String role, String stageCode) {
        String stage = normalize(stageCode);
        String normalizedRole = normalizeRole(role);
        if (PRE_AI_FULL_OPERATOR_ROLES.contains(normalizedRole) && STAGE_EDITORS.containsKey(stage)) return true;
        return STAGE_EDITORS.getOrDefault(stage, Set.of()).contains(normalizedRole);
    }

    public boolean canCorrectStage(String role, String stageCode) {
        return !"REVIEW".equals(normalize(stageCode))
            && canEditStage(role, stageCode);
    }

    public boolean canEditAuxiliary(String role, String taskType) {
        String task = normalize(taskType);
        String normalizedRole = normalizeRole(role);
        if (PRE_AI_FULL_OPERATOR_ROLES.contains(normalizedRole) && AUXILIARY_EDITORS.containsKey(task)) return true;
        return AUXILIARY_EDITORS.getOrDefault(task, Set.of()).contains(normalizedRole);
    }

    public boolean canCreateAuxiliary(String role, String taskType) {
        String normalizedRole = normalizeRole(role);
        String normalizedTask = normalize(taskType);
        if (PRE_AI_FULL_OPERATOR_ROLES.contains(normalizedRole) && AUXILIARY_EDITORS.containsKey(normalizedTask)) return true;
        if (Set.of("doctor", "reception").contains(normalizedRole)) return true;
        return AUXILIARY_EDITORS.getOrDefault(normalizedTask, Set.of()).contains(normalizedRole);
    }

    public boolean hasCapability(SessionUser user, String capability) {
        if (user == null || capability == null || capability.isBlank()) return false;
        if (capability.startsWith("inventory:")) return inventoryAccessService.hasCapability(user, capability);
        RolePolicy policy = policies.get(normalizeRole(user.role()));
        if (policy == null) return false;
        if (policy.buttonPermissions().values().stream()
            .flatMap(List::stream)
            .anyMatch(capability::equals)) return true;
        Set<String> roles = PRE_AI_CAPABILITY_ROLES.get(capability);
        if (roles != null && roles.contains(normalizeRole(user.role()))) return true;
        if (capability.startsWith("preai:stage:")) {
            String[] parts = capability.split(":");
            if (parts.length == 4 && "edit".equals(parts[3])) return canEditStage(user.role(), parts[2]);
            if (parts.length == 4 && "correct".equals(parts[3])) return canCorrectStage(user.role(), parts[2]);
        }
        if (capability.startsWith("preai:auxiliary:")) {
            String[] parts = capability.split(":");
            if (parts.length == 4 && "edit".equals(parts[3])) return canEditAuxiliary(user.role(), parts[2]);
            if (parts.length == 4 && "create".equals(parts[3])) return canCreateAuxiliary(user.role(), parts[2]);
        }
        return false;
    }

    private RolePolicy effectivePolicy(SessionUser user, RolePolicy basePolicy) {
        InventoryAccessService.Access inventoryAccess = inventoryAccessService.accessFor(user);
        Set<String> menuPaths = new LinkedHashSet<>(basePolicy.menuPaths());
        if (!menuPaths.contains("*")) {
            menuPaths.removeIf(path -> path.startsWith("/inventory/"));
            menuPaths.addAll(inventoryAccess.menuPaths());
        }
        Map<String, List<String>> buttons = new LinkedHashMap<>(basePolicy.buttonPermissions());
        buttons.entrySet().removeIf(entry -> entry.getValue().stream().anyMatch(value -> value.startsWith("inventory:")));
        buttons.putAll(inventoryAccess.buttonPermissions());
        return new RolePolicy(Set.copyOf(menuPaths), Map.copyOf(buttons));
    }

    private Map<String, StagePermission> stagePermissions(String role) {
        Map<String, StagePermission> result = new LinkedHashMap<>();
        for (String stage : STAGES) {
            result.put(stage, new StagePermission(true, canEditStage(role, stage), canCorrectStage(role, stage)));
        }
        return Map.copyOf(result);
    }

    private Map<String, AuxiliaryPermission> auxiliaryPermissions(String role) {
        Map<String, AuxiliaryPermission> result = new LinkedHashMap<>();
        AUXILIARY_EDITORS.keySet().stream().sorted().forEach(task -> {
            boolean editable = canEditAuxiliary(role, task);
            boolean returnable = PRE_AI_FULL_OPERATOR_ROLES.contains(normalizeRole(role));
            result.put(task, new AuxiliaryPermission(true, editable, returnable));
        });
        return Map.copyOf(result);
    }

    private List<DepartmentOption> authorizedDepartments(String accountId) {
        if (accountId == null || accountId.isBlank()) return List.of();
        return jdbcTemplate.query(
            """
            SELECT d.id, d.code, d.name, d.status, ad.is_primary
            FROM clinic_account_departments ad
            JOIN clinic_departments d ON d.id = ad.department_id
            WHERE ad.account_id = ? AND ad.status = 'ACTIVE' AND d.status = 'ACTIVE'
            ORDER BY ad.is_primary DESC, d.name, d.id
            """,
            (rs, rowNum) -> new DepartmentOption(
                rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getBoolean("is_primary"), rs.getString("status")
            ),
            accountId
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeRole(String value) {
        return RoleCatalog.canonicalize(value);
    }

    private static Set<String> setWith(Set<String> source, String... values) {
        Set<String> result = new LinkedHashSet<>(source);
        result.addAll(Arrays.asList(values));
        return Set.copyOf(result);
    }

    private static List<NavigationShortcut> prioritizeShortcuts(String role, List<NavigationShortcut> source) {
        String preferredPath = switch (role) {
            case "quality" -> "/audit/review";
            case "manager" -> "/templates/ai-document";
            default -> "/pre-ai/encounters";
        };
        List<NavigationShortcut> result = new ArrayList<>(source.size());
        source.stream().filter(item -> preferredPath.equals(item.path())).forEach(result::add);
        source.stream().filter(item -> !preferredPath.equals(item.path())).forEach(result::add);
        return List.copyOf(result);
    }

    private List<NavigationMenu> filterMenus(List<NavigationMenu> source, RolePolicy policy) {
        return filterMenus(source, policy.menuPaths());
    }

    List<NavigationMenu> filterMenus(List<NavigationMenu> source, Set<String> allowedPaths) {
        boolean allowAll = allowedPaths.contains("*");
        List<NavigationMenu> result = new ArrayList<>();
        for (NavigationMenu item : source) {
            List<NavigationMenu> children = item.children() == null ? List.of() : filterMenus(item.children(), allowedPaths);
            boolean compatibilityParent = item.meta().isHide() && allowedPaths.stream()
                .anyMatch(path -> path.startsWith(item.path() + "/"));
            if (!allowAll && !allowedPaths.contains(item.path()) && children.isEmpty() && !compatibilityParent) continue;
            String redirect = item.redirect();
            if (!children.isEmpty() && redirect != null && !allowAll && !allowedPaths.contains(redirect)) {
                redirect = children.get(0).redirect() == null ? children.get(0).path() : children.get(0).redirect();
            }
            if ("/inventory".equals(item.path()) && redirect != null) {
                String inventoryRedirect = redirect;
                children = children.stream()
                    .map(child -> "/inventory/manage".equals(child.path())
                        ? new NavigationMenu(
                            child.path(), child.name(), child.component(), inventoryRedirect, child.meta(), child.children()
                        )
                        : child)
                    .toList();
            }
            result.add(new NavigationMenu(
                item.path(), item.name(), item.component(), redirect, item.meta(), children.isEmpty() ? null : children
            ));
        }
        if (!allowAll) {
            List<NavigationMenu> filteredResult = List.copyOf(result);
            result = filteredResult.stream().map(item -> {
                String groupPath = switch (item.path()) {
                    case "/workbench", "/templates" -> "/navigation/materials-documents";
                    default -> null;
                };
                if (groupPath == null || item.redirect() == null || allowedPaths.contains(item.redirect())) return item;
                NavigationMenu group = filteredResult.stream()
                    .filter(candidate -> groupPath.equals(candidate.path()))
                    .findFirst()
                    .orElse(null);
                if (group == null || group.redirect() == null) return item;
                return new NavigationMenu(item.path(), item.name(), item.component(), group.redirect(), item.meta(), item.children());
            }).toList();
        }
        return List.copyOf(result);
    }

    private static Set<String> effectiveMenuPaths(Set<String> source) {
        if (source.contains("*")) return source;
        Set<String> result = new LinkedHashSet<>(source);
        boolean hasInventoryAccess = source.stream().anyMatch(path -> path.startsWith("/inventory/"));
        if (hasInventoryAccess) result.add("/inventory/manage");
        return Set.copyOf(result);
    }

    private static List<NavigationMenu> buildMenus() {
        List<NavigationMenu> result = new ArrayList<>();
        result.add(page("/welcome/index", "welcome", "/welcome/index", "主页", "HomeFilled", false, false, true));
        result.add(page("/home/index", "home", "/home/index", "我的待办", "List", false, false, false));
        result.add(group("/navigation/patient-collaboration", "patientCollaboration", "/pre-ai/encounters", "患者就诊", "UserFilled",
            page("/pre-ai/encounters", "preAiEncounters", "/preAi/encounters/index", "登记与事实采集", "EditPen", false, false, false),
            page("/encounters/active", "encounterActive", "/encounters/active/index", "患者进度", "Connection", false, false, false),
            page("/patients/list", "patientList", "/patients/list/index", "患者档案查询", "Search", false, false, false),
            pageWithActiveMenu("/patients/detail/:id", "patientDetail", "/patients/detail/index", "患者档案详情", "Document", "/patients/list")
        ));
        result.add(group("/navigation/materials-documents", "materialsDocuments", "/workbench/upload", "资料录入与文书", "FolderOpened",
            page("/workbench/upload", "workbenchUpload", "/workbench/upload/index", "患者资料上传", "UploadFilled", false, false, false),
            page("/workbench/lab-report", "workbenchLabReport", "/workbench/labReport/index", "检验报告填写", "Memo", false, false, false),
            page("/templates/ai-document", "aiDocumentGenerator", "/templates/aiDocument/index", "通用文书生成", "DocumentAdd", false, false, false),
            redirect("/workbench/legacy", "workbenchLegacy", "/workbench/upload?tab=legacy", "旧共享病历导入", "FolderOpened", true)
        ));
        result.add(group("/navigation/business-workbench", "businessWorkbench", "/tcm-pharmacy/workbench", "业务工作台", "Operation",
            group("/inventory", "inventory", "/inventory/overview", "进销存管理", "Box",
                page("/inventory/overview", "inventoryOverview", "/inventory/manage/index", "今日待办", "Monitor", false, false, false),
                page("/inventory/executive", "inventoryExecutive", "/inventory/manage/index", "管理看板", "TrendCharts", false, false, false),
                page("/inventory/requests", "inventoryRequests", "/inventory/manage/index", "申领与签收", "Tickets", false, false, false),
                page("/inventory/stock", "inventoryStock", "/inventory/manage/index", "入库与库存", "Box", false, false, false),
                page("/inventory/controls", "inventoryControls", "/inventory/manage/index", "盘点与报损", "SetUp", false, false, false),
                page("/inventory/packages", "inventoryPackages", "/inventory/manage/index", "患者耗材套餐", "CollectionTag", false, false, false),
                page("/inventory/weekly", "inventoryWeekly", "/inventory/manage/index", "周用量核对", "DataLine", false, false, false),
                page("/inventory/trace", "inventoryTrace", "/inventory/manage/index", "出入库记录", "Search", false, false, false),
                page("/inventory/items", "inventoryItems", "/inventory/manage/index", "物资设置", "Goods", false, false, false),
                page("/inventory/daily", "inventoryDaily", "/inventory/manage/index", "每日核验报表", "DocumentChecked", false, false, false),
                page("/inventory/roles", "inventoryRoles", "/inventory/manage/index", "岗位与权限", "UserFilled", false, false, false),
                redirect("/inventory/manage", "inventoryManageCompatibility", "/inventory/overview", "进销存兼容入口", "Link", true)
            ),
            page("/tcm-pharmacy/workbench", "tcmPharmacyWorkbench", "/tcmPharmacy/workbench/index", "中药房工作台", "FirstAidKit", false, false, false),
            page("/tcm-pharmacy/display", "tcmPharmacyDisplayMenu", "/tcmPharmacy/display/index", "取药展示大屏", "Monitor", true, true, false),
            page("/tcm-pharmacy/clinic-queue/workbench", "clinicQueueWorkbench", "/clinicQueue/workbench/index", "检查接诊叫号", "Guide", false, false, false),
            page("/tcm-pharmacy/clinic-queue/display", "clinicQueueDisplayMenu", "/clinicQueue/display/index", "检查接诊大屏", "Monitor", true, true, false)
        ));
        result.add(group("/navigation/quality-audit", "qualityAudit", "/audit/review", "审核与追溯", "DocumentChecked",
            page("/audit/review", "auditReview", "/audit/review/index", "待审病历", "Tickets", false, false, false),
            page("/documents/recycle", "documentRecycle", "/documents/recycle/index", "作废资料恢复", "RefreshLeft", false, false, false),
            page("/audit/log", "auditLog", "/audit/log/index", "操作记录查询", "Search", false, false, false)
        ));
        result.add(group("/system", "system", "/system/accountManage", "系统管理", "Tools",
            group("/system/organization", "systemOrganization", "/system/accountManage", "组织与账号", "OfficeBuilding",
                page("/system/accountManage", "accountManage", "/system/accountManage/index", "账号管理", "User", false, false, false),
                page("/system/departmentManage", "departmentManage", "/system/departmentManage/index", "科室管理", "OfficeBuilding", false, false, false)
            ),
            group("/system/permission-policy", "systemPermissionPolicy", "/system/roleManage", "权限策略", "Lock",
                page("/system/roleManage", "roleManage", "/system/roleManage/index", "角色权限", "Lock", false, false, false),
                page("/system/menuMange", "menuMange", "/system/menuMange/index", "菜单权限（只读）", "Operation", false, false, false)
            ),
            group("/system/medical-record-rules", "systemMedicalRecordRules", "/templates/record", "病历规则", "DocumentCopy",
                page("/templates/record", "recordTemplate", "/templates/record/index", "模板与字段权限", "DocumentCopy", false, false, false),
                page("/system/dictManage", "dictManage", "/system/dictManage/index", "资料字典", "Collection", false, false, false)
            ),
            group("/system/ai-management", "systemAiManagement", "/system/aiConfig", "AI管理", "Setting",
                page("/system/aiConfig", "aiConfig", "/system/aiConfig/index", "AI接口配置", "Setting", false, false, false),
                page("/system/aiAssistantAnalysis", "aiAssistantAnalysis", "/system/aiAssistantAnalysis/index", "AI使用分析", "DataAnalysis", false, false, false)
            ),
            page("/system/dataMaintenance", "dataMaintenance", "/system/dataMaintenance/index", "数据维护", "Tools", false, false, false),
            page("/system/systemLog", "systemLog", "/system/systemLog/index", "系统日志", "Notebook", true, false, false)
        ));

        result.addAll(List.of(
            compatibility("/pre-ai", "preAi", "/pre-ai/encounters"),
            compatibility("/workbench", "workbench", "/workbench/upload"),
            compatibility("/patients", "patients", "/patients/list"),
            compatibility("/encounters", "encounters", "/encounters/active"),
            compatibility("/templates", "templates", "/templates/record"),
            compatibility("/documents", "documents", "/documents/recycle"),
            compatibility("/audit", "audit", "/audit/log"),
            compatibility("/tcm-pharmacy", "tcmPharmacy", "/tcm-pharmacy/clinic-queue/workbench"),
            compatibility("/tcm-pharmacy/tcm", "tcmPharmacyBusiness", "/tcm-pharmacy/workbench"),
            compatibility("/tcm-pharmacy/clinic-queue", "clinicQueueBusiness", "/tcm-pharmacy/clinic-queue/workbench")
        ));
        return List.copyOf(result);
    }

    private static Map<String, RolePolicy> buildPolicies() {
        Map<String, RolePolicy> result = new LinkedHashMap<>();
        Map<String, List<String>> administratorButtons = permissions(
            "home=view",
            "workbenchUpload=patient:search,document:read",
            "workbenchLabReport=patient:search,field:read,document:read",
            "encounterActive=patient:read,field:read",
            "recordTemplate=field:read",
            "patientList=patient:read",
            "patientDetail=field:read,document:read,document:download",
            "documentRecycle=document:read",
            "auditReview=audit:read",
            "auditLog=audit:read,audit:export",
            "accountManage=user:create,user:update,user:disable,user:resetPassword",
            "roleManage=role:read",
            "departmentManage=department:create,department:update,department:deactivate",
            "dictManage=dict:create,dict:update",
            "menuMange=menu:read",
            "aiConfig=ai:config:read,ai:config:update",
            "aiAssistantAnalysis=ai:usage:read,ai:template:candidate",
            "systemLog=audit:read",
            "dataMaintenance=maintenance:purge,maintenance:backup",
            "tcmPharmacyWorkbench=pharmacy:read",
            "tcmPharmacyDisplayMenu=display:read",
            "clinicQueueWorkbench=queue:read,audit:read",
            "clinicQueueDisplayMenu=display:read",
            "inventoryOverview=inventory:read,inventory:export",
            "inventoryExecutive=inventory:read,inventory:export",
            "inventoryRequests=inventory:read,inventory:export",
            "inventoryStock=inventory:read,inventory:export",
            "inventoryItems=inventory:read,inventory:item:manage,inventory:export",
            "inventoryWeekly=inventory:read,inventory:export",
            "inventoryPackages=inventory:read",
            "inventoryControls=inventory:read,inventory:export",
            "inventoryTrace=inventory:read,inventory:export",
            "inventoryDaily=inventory:read,inventory:export",
            "inventoryRoles=inventory:read,inventory:role:manage"
        );
        result.put("admin", new RolePolicy(Set.of("*"), administratorButtons));

        Set<String> patientFlow = paths("/welcome/index", "/home/index", "/patients/list", "/patients/detail/:id", "/encounters/active");
        Set<String> materials = paths("/workbench/upload", "/workbench/lab-report", "/templates/record", "/templates/ai-document");
        Set<String> preAi = paths("/pre-ai/encounters");
        Set<String> clinicQueue = paths("/tcm-pharmacy/clinic-queue/workbench", "/tcm-pharmacy/clinic-queue/display");
        Set<String> tcmPharmacy = paths("/tcm-pharmacy/workbench", "/tcm-pharmacy/display");
        Set<String> inventoryStaff = paths(
            "/inventory/overview", "/inventory/requests", "/inventory/weekly", "/inventory/packages",
            "/inventory/controls", "/inventory/manage"
        );
        Set<String> inventoryQuality = paths(
            "/inventory/overview", "/inventory/executive", "/inventory/weekly", "/inventory/packages",
            "/inventory/trace", "/inventory/manage"
        );
        Set<String> inventoryWarehouse = paths(
            "/inventory/overview", "/inventory/requests", "/inventory/stock", "/inventory/items", "/inventory/weekly",
            "/inventory/packages", "/inventory/controls", "/inventory/trace", "/inventory/manage"
        );
        Map<String, List<String>> inventoryStaffButtons = permissions(
            "inventoryOverview=inventory:read,inventory:request,inventory:receive,inventory:count",
            "inventoryRequests=inventory:read,inventory:request,inventory:receive",
            "inventoryWeekly=inventory:read",
            "inventoryPackages=inventory:read",
            "inventoryControls=inventory:read,inventory:count"
        );
        Map<String, List<String>> inventoryQualityButtons = permissions(
            "inventoryOverview=inventory:read,inventory:export",
            "inventoryExecutive=inventory:read,inventory:export",
            "inventoryWeekly=inventory:read,inventory:rule,inventory:confirm,inventory:export",
            "inventoryPackages=inventory:read,inventory:rule,inventory:retry",
            "inventoryTrace=inventory:read,inventory:export"
        );
        Map<String, List<String>> inventoryWarehouseButtons = permissions(
            "inventoryOverview=inventory:read,inventory:approve,inventory:issue,inventory:receive,inventory:count,inventory:export",
            "inventoryRequests=inventory:read,inventory:approve,inventory:issue,inventory:receive,inventory:export",
            "inventoryStock=inventory:read,inventory:issue,inventory:receive,inventory:count,inventory:export",
            "inventoryItems=inventory:read,inventory:item:manage,inventory:issue,inventory:export",
            "inventoryWeekly=inventory:read,inventory:count,inventory:export",
            "inventoryPackages=inventory:read,inventory:retry",
            "inventoryControls=inventory:read,inventory:receive,inventory:count,inventory:export",
            "inventoryTrace=inventory:read,inventory:export"
        );

        result.put("frontdesk", role(union(patientFlow, materials, preAi, clinicQueue, inventoryStaff), mergePermissions(permissions(
            "home=view", "workbenchUpload=patient:search,document:upload", "workbenchLabReport=patient:search,field:read,document:read",
            "encounterActive=patient:read,field:read", "recordTemplate=field:read", "patientList=patient:create,patient:read,patient:update",
            "patientDetail=field:read,field:edit,document:read,document:upload,document:download",
            "clinicQueueWorkbench=queue:read,queue:issue,queue:intervene,room:control,audit:read", "clinicQueueDisplayMenu=display:read,announcement:play"
        ), inventoryStaffButtons)));
        result.put("inspection", role(union(patientFlow, materials, preAi, clinicQueue, inventoryStaff), mergePermissions(permissions(
            "home=view", "workbenchUpload=patient:search,document:upload", "workbenchLabReport=patient:search,field:read,document:read",
            "encounterActive=patient:read,field:read", "recordTemplate=field:read", "patientList=patient:read",
            "patientDetail=field:read,field:edit,document:read,document:upload",
            "clinicQueueWorkbench=queue:read,inspection:operate,room:control,audit:read", "clinicQueueDisplayMenu=display:read,announcement:play"
        ), inventoryStaffButtons)));
        result.put("reception", role(union(patientFlow, preAi, clinicQueue), permissions(
            "home=view", "encounterActive=patient:read,field:read", "patientList=patient:read", "patientDetail=field:read,field:edit,document:read",
            "clinicQueueWorkbench=queue:read,reception:operate,room:control,audit:read", "clinicQueueDisplayMenu=display:read,announcement:play"
        )));

        Map<String, List<String>> diagnosticButtons = mergePermissions(permissions(
            "home=view", "workbenchUpload=patient:search,document:upload", "workbenchLabReport=patient:search,field:read,document:upload",
            "encounterActive=patient:read,field:read", "recordTemplate=field:read", "patientList=patient:read",
            "patientDetail=field:read,field:edit,document:read,document:upload"
        ), inventoryStaffButtons);
        result.put("lab", role(union(patientFlow, materials, preAi, inventoryStaff), diagnosticButtons));
        result.put("ecg", role(union(patientFlow, materials, preAi, inventoryStaff), diagnosticButtons));
        result.put("ultrasound", role(union(patientFlow, materials, preAi, inventoryStaff), diagnosticButtons));
        result.put("nurse", role(union(patientFlow, materials, preAi, inventoryStaff), diagnosticButtons));

        result.put("tcm", role(union(patientFlow, preAi, tcmPharmacy), permissions(
            "home=view", "tcmPharmacyWorkbench=prescription:create,prescription:submit,pharmacy:read", "tcmPharmacyDisplayMenu=display:read"
        )));
        result.put("tcm_pharmacy", role(union(paths("/welcome/index", "/home/index"), tcmPharmacy), permissions(
            "home=view", "tcmPharmacyWorkbench=pharmacy:read,charge:confirm,review:execute,dispensing:execute,decoction:execute,pickup:execute",
            "tcmPharmacyDisplayMenu=display:read,announcement:play"
        )));
        result.put("display", role(paths("/tcm-pharmacy/clinic-queue/display", "/tcm-pharmacy/display"), permissions(
            "clinicQueueDisplayMenu=display:read,announcement:play",
            "tcmPharmacyDisplayMenu=display:read,announcement:play"
        )));

        result.put("doctor", role(union(patientFlow, preAi, paths("/workbench/lab-report", "/templates/record", "/templates/ai-document"), tcmPharmacy, clinicQueue, inventoryStaff), mergePermissions(permissions(
            "home=view", "workbenchLabReport=patient:search,field:edit,document:upload", "encounterActive=patient:read,field:read",
            "recordTemplate=field:read", "patientList=patient:read", "patientDetail=field:read,field:edit,document:read,document:download",
            "tcmPharmacyWorkbench=prescription:create,prescription:submit,pharmacy:read", "tcmPharmacyDisplayMenu=display:read",
            "clinicQueueWorkbench=queue:read,reception:operate,room:control,audit:read", "clinicQueueDisplayMenu=display:read,announcement:play"
        ), inventoryStaffButtons)));
        result.put("quality", role(union(patientFlow, inventoryQuality, paths(
            "/workbench/lab-report", "/templates/record", "/templates/ai-document", "/documents/recycle", "/audit/review", "/audit/log"
        )), mergePermissions(permissions(
            "home=view", "workbenchLabReport=patient:search,field:read,document:read", "encounterActive=patient:read,field:read",
            "recordTemplate=field:read", "patientList=patient:read", "patientDetail=field:read,document:read,document:void,document:download",
            "documentRecycle=document:restore,document:read", "auditReview=audit:read,quality:approve,quality:reject", "auditLog=audit:read,audit:export"
        ), inventoryQualityButtons)));
        result.put("manager", role(paths(
            "/welcome/index", "/home/index", "/inventory/overview", "/inventory/executive"
        ), permissions(
            "home=view", "inventoryOverview=inventory:read,inventory:export",
            "inventoryExecutive=inventory:read,inventory:export"
        )));
        result.put("warehouse", role(union(paths("/welcome/index", "/home/index"), inventoryWarehouse), mergePermissions(
            permissions("home=view"), inventoryWarehouseButtons
        )));
        return Map.copyOf(result);
    }

    private static RolePolicy role(Set<String> paths, Map<String, List<String>> buttons) {
        return new RolePolicy(Set.copyOf(paths), Map.copyOf(buttons));
    }

    private static Map<String, List<String>> permissions(String... definitions) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String definition : definitions) {
            int separator = definition.indexOf('=');
            String routeName = definition.substring(0, separator);
            List<String> values = Arrays.stream(definition.substring(separator + 1).split(","))
                .filter(value -> !value.isBlank())
                .toList();
            result.put(routeName, values);
        }
        return Map.copyOf(result);
    }

    @SafeVarargs
    private static Map<String, List<String>> mergePermissions(Map<String, List<String>>... groups) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map<String, List<String>> group : groups) result.putAll(group);
        return Map.copyOf(result);
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... groups) {
        Set<String> result = new LinkedHashSet<>();
        for (Set<String> group : groups) result.addAll(group);
        return Set.copyOf(result);
    }

    private static Set<String> paths(String... values) {
        return Set.of(values);
    }

    private static NavigationShortcut shortcut(String title, String desc, String icon, String path) {
        return new NavigationShortcut(title, desc, icon, path);
    }

    private static NavigationMenu group(
        String path,
        String name,
        String redirect,
        String title,
        String icon,
        NavigationMenu... children
    ) {
        return new NavigationMenu(path, name, null, redirect, meta(title, icon, false, false, false), List.of(children));
    }

    private static NavigationMenu page(
        String path,
        String name,
        String component,
        String title,
        String icon,
        boolean hidden,
        boolean full,
        boolean affix
    ) {
        return new NavigationMenu(path, name, component, null, meta(title, icon, hidden, full, affix), null);
    }

    private static NavigationMenu pageWithActiveMenu(
        String path,
        String name,
        String component,
        String title,
        String icon,
        String activeMenu
    ) {
        NavigationMeta meta = new NavigationMeta(icon, title, activeMenu, "", true, false, false, true);
        return new NavigationMenu(path, name, component, null, meta, null);
    }

    private static NavigationMenu redirect(String path, String name, String redirect, String title, String icon, boolean hidden) {
        return new NavigationMenu(path, name, null, redirect, meta(title, icon, hidden, false, false), null);
    }

    private static NavigationMenu compatibility(String path, String name, String redirect) {
        return redirect(path, name, redirect, "兼容入口", "Link", true);
    }

    private static NavigationMeta meta(String title, String icon, boolean hidden, boolean full, boolean affix) {
        return new NavigationMeta(icon, title, null, "", hidden, full, affix, !full);
    }

    private record RolePolicy(Set<String> menuPaths, Map<String, List<String>> buttonPermissions) {
        boolean allMenus() {
            return menuPaths.contains("*");
        }
    }
}

package com.coshare.patientrecord.auth.service;

import com.coshare.patientrecord.auth.dto.NavigationMenu;
import com.coshare.patientrecord.auth.dto.NavigationMeta;
import com.coshare.patientrecord.auth.dto.NavigationResult;
import com.coshare.patientrecord.auth.dto.NavigationShortcut;
import com.coshare.patientrecord.auth.dto.SessionUser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("mysql")
public class AuthNavigationService {

    public static final String VERSION = "2026.07.18.1";
    private static final Logger log = LoggerFactory.getLogger(AuthNavigationService.class);

    private final List<NavigationMenu> menus = buildMenus();
    private final Map<String, RolePolicy> policies = buildPolicies();
    private final List<NavigationShortcut> shortcuts = List.of(
        shortcut("诊疗流程", "查看患者诊疗进度", "Connection", "/encounters/active"),
        shortcut("患者档案", "按姓名和门诊号查询", "UserFilled", "/patients/list"),
        shortcut("科室资料上传", "定位患者后上传资料", "UploadFilled", "/workbench/upload"),
        shortcut("检验报告录入", "录入并复核检验结果", "Memo", "/workbench/lab-report"),
        shortcut("文书生成", "生成并下载临床文书", "DocumentAdd", "/templates/ai-document"),
        shortcut("进销存管理", "科室申领、库存与自动扣减", "Box", "/inventory/overview"),
        shortcut("中药房工作台", "收费、审方、调剂和取药", "MedicineBox", "/tcm-pharmacy/workbench"),
        shortcut("检查接诊叫号", "管理检查与接诊双队列", "Guide", "/tcm-pharmacy/clinic-queue/workbench"),
        shortcut("档案审核", "退回整改或通过归档", "Tickets", "/audit/review"),
        shortcut("作废与恢复", "恢复误作废资料", "RefreshLeft", "/documents/recycle"),
        shortcut("操作审计", "追踪关键资料改动", "DocumentChecked", "/audit/log")
    );

    public NavigationResult navigationFor(SessionUser user) {
        RolePolicy policy = policies.get(user.role());
        if (policy == null) {
            log.warn(
                "SECURITY_AUDIT navigation denied for unknown role: userId={}, username={}, role={}",
                user.id(),
                user.username(),
                user.role()
            );
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号角色未配置导航权限，请联系系统管理员");
        }

        List<NavigationMenu> authorizedMenus = filterMenus(menus, policy.menuPaths());
        List<NavigationShortcut> authorizedShortcuts = shortcuts.stream()
            .filter(item -> policy.allMenus() || policy.menuPaths().contains(item.path()))
            .toList();
        return new NavigationResult(VERSION, authorizedMenus, policy.buttonPermissions(), authorizedShortcuts);
    }

    private List<NavigationMenu> filterMenus(List<NavigationMenu> source, RolePolicy policy) {
        return filterMenus(source, policy.menuPaths());
    }

    private List<NavigationMenu> filterMenus(List<NavigationMenu> source, Set<String> allowedPaths) {
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
            result.add(new NavigationMenu(
                item.path(), item.name(), item.component(), redirect, item.meta(), children.isEmpty() ? null : children
            ));
        }
        return List.copyOf(result);
    }

    private static List<NavigationMenu> buildMenus() {
        List<NavigationMenu> result = new ArrayList<>();
        result.add(page("/home/index", "home", "/home/index", "我的待办", "HomeFilled", false, false, true));
        result.add(group("/navigation/patient-collaboration", "patientCollaboration", "/patients/list", "患者协作", "UserFilled",
            page("/patients/list", "patientList", "/patients/list/index", "患者档案", "List", false, false, false),
            pageWithActiveMenu("/patients/detail/:id", "patientDetail", "/patients/detail/index", "患者流程详情", "Document", "/patients/list"),
            page("/pre-ai/encounters", "preAiEncounters", "/preAi/encounters/index", "前置事实采集", "EditPen", false, false, false),
            page("/encounters/active", "encounterActive", "/encounters/active/index", "诊疗流程", "Connection", false, false, false)
        ));
        result.add(group("/navigation/materials-documents", "materialsDocuments", "/workbench/upload", "资料与文书", "Files",
            page("/workbench/upload", "workbenchUpload", "/workbench/upload/index", "科室资料上传", "Upload", false, false, false),
            page("/workbench/lab-report", "workbenchLabReport", "/workbench/labReport/index", "检验报告录入", "Memo", false, false, false),
            page("/templates/ai-document", "aiDocumentGenerator", "/templates/aiDocument/index", "文书生成", "DocumentAdd", false, false, false),
            redirect("/workbench/legacy", "workbenchLegacy", "/workbench/upload?tab=legacy", "旧共享病历导入", "FolderOpened", true)
        ));
        result.add(group("/navigation/business-workbench", "businessWorkbench", "/tcm-pharmacy/workbench", "业务工作台", "FirstAidKit",
            group("/inventory", "inventory", "/inventory/overview", "进销存管理", "Box",
                page("/inventory/overview", "inventoryOverview", "/inventory/manage/index", "进销存主控台", "Monitor", false, false, false),
                page("/inventory/executive", "inventoryExecutive", "/inventory/manage/index", "领导驾驶舱", "TrendCharts", false, false, false),
                page("/inventory/requests", "inventoryRequests", "/inventory/manage/index", "科室申领审批", "Tickets", false, false, false),
                page("/inventory/stock", "inventoryStock", "/inventory/manage/index", "库存与批次", "Box", false, false, false),
                page("/inventory/items", "inventoryItems", "/inventory/manage/index", "物资档案", "Goods", false, false, false),
                page("/inventory/weekly", "inventoryWeekly", "/inventory/manage/index", "周消耗预估", "DataLine", false, false, false),
                page("/inventory/packages", "inventoryPackages", "/inventory/manage/index", "使用套餐与自动扣减", "CollectionTag", false, false, false),
                page("/inventory/controls", "inventoryControls", "/inventory/manage/index", "盘点与控制", "SetUp", false, false, false),
                page("/inventory/trace", "inventoryTrace", "/inventory/manage/index", "全链路追溯", "Search", false, false, false)
            ),
            page("/tcm-pharmacy/workbench", "tcmPharmacyWorkbench", "/tcmPharmacy/workbench/index", "中药房工作台", "MedicineBox", false, false, false),
            page("/tcm-pharmacy/display", "tcmPharmacyDisplayMenu", "/tcmPharmacy/display/index", "取药展示大屏", "Monitor", true, true, false),
            page("/tcm-pharmacy/clinic-queue/workbench", "clinicQueueWorkbench", "/clinicQueue/workbench/index", "检查接诊叫号", "Guide", false, false, false),
            page("/tcm-pharmacy/clinic-queue/display", "clinicQueueDisplayMenu", "/clinicQueue/display/index", "检查接诊大屏", "Monitor", true, true, false)
        ));
        result.add(group("/navigation/quality-audit", "qualityAudit", "/audit/review", "质控与审计", "Tickets",
            page("/audit/review", "auditReview", "/audit/review/index", "档案审核", "Tickets", false, false, false),
            page("/documents/recycle", "documentRecycle", "/documents/recycle/index", "作废与恢复", "RefreshLeft", false, false, false),
            page("/audit/log", "auditLog", "/audit/log/index", "操作审计", "DocumentChecked", false, false, false)
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
        Map<String, List<String>> allButtons = permissions(
            "home=view",
            "workbenchUpload=patient:search,document:upload",
            "workbenchLabReport=patient:search,field:read,field:edit,document:read,document:upload",
            "encounterActive=patient:read,field:read",
            "recordTemplate=field:read",
            "patientList=patient:create,patient:read,patient:update",
            "patientDetail=field:read,field:edit,document:read,document:upload,document:void,document:download",
            "documentRecycle=document:restore,document:read",
            "auditReview=audit:read,quality:approve,quality:reject",
            "auditLog=audit:read,audit:export",
            "accountManage=user:create,user:update,user:disable,user:resetPassword,user:delete",
            "roleManage=role:create,role:update,role:grant,role:delete",
            "departmentManage=department:create,department:update,department:delete",
            "dictManage=dict:create,dict:update",
            "menuMange=menu:read",
            "aiConfig=ai:config:read,ai:config:update",
            "aiAssistantAnalysis=ai:usage:read,ai:template:candidate",
            "systemLog=audit:read",
            "tcmPharmacyWorkbench=prescription:create,prescription:submit,pharmacy:read,charge:confirm,review:execute,dispensing:execute,decoction:execute,pickup:execute",
            "tcmPharmacyDisplayMenu=display:read,announcement:play",
            "clinicQueueWorkbench=queue:read,queue:issue,queue:intervene,inspection:operate,reception:operate,room:control,audit:read",
            "clinicQueueDisplayMenu=display:read,announcement:play",
            "inventoryOverview=inventory:read,inventory:request,inventory:receive,inventory:approve,inventory:count,inventory:export",
            "inventoryExecutive=inventory:read,inventory:approve,inventory:count,inventory:export",
            "inventoryRequests=inventory:read,inventory:request,inventory:receive,inventory:approve,inventory:export",
            "inventoryStock=inventory:read,inventory:issue,inventory:count,inventory:export",
            "inventoryItems=inventory:read,inventory:issue,inventory:export",
            "inventoryWeekly=inventory:read,inventory:request,inventory:count,inventory:export",
            "inventoryPackages=inventory:read,inventory:approve",
            "inventoryControls=inventory:read,inventory:count,inventory:export",
            "inventoryTrace=inventory:read,inventory:export"
        );
        result.put("admin", new RolePolicy(Set.of("*"), allButtons));

        Set<String> patientFlow = paths("/home/index", "/patients/list", "/patients/detail/:id", "/encounters/active");
        Set<String> materials = paths("/workbench/upload", "/workbench/lab-report", "/templates/record", "/templates/ai-document");
        Set<String> preAi = paths("/pre-ai/encounters");
        Set<String> clinicQueue = paths("/tcm-pharmacy/clinic-queue/workbench", "/tcm-pharmacy/clinic-queue/display");
        Set<String> tcmPharmacy = paths("/tcm-pharmacy/workbench", "/tcm-pharmacy/display");
        Set<String> inventoryStaff = paths(
            "/inventory/overview", "/inventory/requests", "/inventory/weekly", "/inventory/packages"
        );
        Set<String> inventoryQuality = paths(
            "/inventory/overview", "/inventory/executive", "/inventory/requests", "/inventory/stock", "/inventory/items", "/inventory/weekly",
            "/inventory/packages", "/inventory/controls", "/inventory/trace"
        );
        Map<String, List<String>> inventoryStaffButtons = permissions(
            "inventoryOverview=inventory:read,inventory:request,inventory:receive",
            "inventoryRequests=inventory:read,inventory:request,inventory:receive",
            "inventoryWeekly=inventory:read,inventory:request",
            "inventoryPackages=inventory:read"
        );
        Map<String, List<String>> inventoryQualityButtons = permissions(
            "inventoryOverview=inventory:read,inventory:approve,inventory:count,inventory:export",
            "inventoryExecutive=inventory:read,inventory:approve,inventory:count,inventory:export",
            "inventoryRequests=inventory:read,inventory:approve,inventory:export",
            "inventoryStock=inventory:read,inventory:issue,inventory:count,inventory:export",
            "inventoryItems=inventory:read,inventory:issue,inventory:export",
            "inventoryWeekly=inventory:read,inventory:count,inventory:export",
            "inventoryPackages=inventory:read,inventory:approve",
            "inventoryControls=inventory:read,inventory:count,inventory:export",
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
        result.put("lab", role(union(patientFlow, materials, inventoryStaff), diagnosticButtons));
        result.put("ecg", role(union(patientFlow, materials, inventoryStaff), diagnosticButtons));
        result.put("ultrasound", role(union(patientFlow, materials, inventoryStaff), diagnosticButtons));
        result.put("nurse", role(union(patientFlow, materials, inventoryStaff), diagnosticButtons));
        result.put("nursing", role(union(patientFlow, materials, inventoryStaff), diagnosticButtons));

        result.put("tcm", role(union(patientFlow, tcmPharmacy), permissions(
            "home=view", "tcmPharmacyWorkbench=prescription:create,prescription:submit,pharmacy:read", "tcmPharmacyDisplayMenu=display:read"
        )));
        result.put("tcmPharmacyOperator", role(tcmPharmacy, permissions(
            "tcmPharmacyWorkbench=pharmacy:read,charge:confirm,review:execute,dispensing:execute,decoction:execute,pickup:execute",
            "tcmPharmacyDisplayMenu=display:read,announcement:play"
        )));
        Map<String, List<String>> pharmacyButtons = permissions(
            "home=view", "tcmPharmacyWorkbench=charge:confirm,review:execute,dispensing:execute,pickup:execute",
            "tcmPharmacyDisplayMenu=display:read,announcement:play"
        );
        result.put("pharmacist", role(union(paths("/home/index"), tcmPharmacy), pharmacyButtons));
        result.put("pharmacy", role(union(paths("/home/index"), tcmPharmacy), pharmacyButtons));
        result.put("decoction", role(union(paths("/home/index"), tcmPharmacy), permissions(
            "home=view", "tcmPharmacyWorkbench=decoction:execute,pharmacy:read", "tcmPharmacyDisplayMenu=display:read"
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
            "/home/index", "/templates/record", "/templates/ai-document",
            "/inventory/overview", "/inventory/executive", "/inventory/items", "/inventory/packages"
        ), permissions(
            "home=view", "recordTemplate=field:read", "inventoryOverview=inventory:read",
            "inventoryExecutive=inventory:read,inventory:export", "inventoryItems=inventory:read", "inventoryPackages=inventory:read"
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

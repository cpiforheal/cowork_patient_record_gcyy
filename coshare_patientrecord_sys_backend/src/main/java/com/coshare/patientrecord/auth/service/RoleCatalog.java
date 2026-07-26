package com.coshare.patientrecord.auth.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Server-owned role directory. Database role rows are a read-only projection of this catalog. */
public final class RoleCatalog {

    private static final List<RoleDefinition> DEFINITIONS = List.of(
        role("admin", "系统管理员", "维护账号、组织、配置、备份和系统数据", "系统管理、数据维护", "配置维护、账号管理、只读排障", "全院系统配置；患者临床事实只读"),
        role("manager", "管理负责人", "查看运营汇总并完成管理导出", "管理看板、汇总报表", "查看汇总、导出", "全院汇总；不可读取患者明细"),
        role("quality", "质控与病案", "审核病历、退回整改并完成归档审计", "待审病历、操作追溯、周度对账", "审核、退回、归档、审计", "全院患者只读；质控动作可写"),
        role("display", "展示终端", "展示脱敏的候诊和取药信息", "候诊大屏、取药大屏", "只读展示、播报", "脱敏公开信息"),
        role("frontdesk", "登记前台", "登记患者并采集就诊前置事实", "登记与事实采集、申领签收", "登记、修正登记信息、确认收费", "授权科室患者"),
        role("reception", "接诊岗位", "完成接诊阶段事实和队列操作", "患者进度、接诊队列", "接诊、叫号、完成接诊", "授权科室或本人任务"),
        role("inspection", "检查岗位", "完成检查阶段和内镜辅助结果", "患者进度、检查队列、资料上传", "检查、内镜结果、叫号", "授权科室或本人任务"),
        role("tcm", "中医岗位", "完成中医诊疗事实并开具中药处方", "登记与事实采集、中医处方", "中医诊疗、开方、提交处方", "授权科室或本人任务"),
        role("doctor", "医生岗位", "完成医生诊断、复核和病历定稿", "登记与事实采集、病历文书", "医生诊断、复核、定稿", "授权科室或本人任务"),
        role("nurse", "护理与手术", "采集生命体征并完成护理、手术阶段事实", "登记与事实采集、护理资料", "生命体征、护理、手术阶段", "授权科室或本人任务"),
        role("lab", "检验岗位", "填写并复核检验报告", "检验报告填写、资料上传", "检验结果录入、复核", "授权科室或本人任务"),
        role("ecg", "心电岗位", "填写心电辅助检查结果", "患者资料上传、心电任务", "心电结果录入", "授权科室或本人任务"),
        role("ultrasound", "超声岗位", "填写超声影像辅助检查结果", "患者资料上传、影像任务", "影像结果录入", "授权科室或本人任务"),
        role("warehouse", "仓库岗位", "负责物资、库存和科室配送作业", "入库与库存、申领审批、盘点报损", "入库、审批、发放、调拨、盘点、报损", "中央仓及授权科室库存"),
        role("tcm_pharmacy", "中药房岗位", "完成收费后审方、调剂、代煎和领取", "中药房工作台、取药大屏", "审方、调剂、代煎、发药", "中药房处方和任务")
    );

    private static final Map<String, RoleDefinition> BY_ROLE;
    private static final Map<String, String> ALIASES = Map.of(
        "nursing", "nurse",
        "tcmpharmacyoperator", "tcm_pharmacy",
        "pharmacist", "tcm_pharmacy",
        "pharmacy", "tcm_pharmacy",
        "decoction", "tcm_pharmacy"
    );

    static {
        Map<String, RoleDefinition> roles = new LinkedHashMap<>();
        DEFINITIONS.forEach(definition -> roles.put(definition.role(), definition));
        BY_ROLE = Map.copyOf(roles);
    }

    private RoleCatalog() {}

    public static List<RoleDefinition> definitions() {
        return DEFINITIONS;
    }

    public static Set<String> roles() {
        return BY_ROLE.keySet();
    }

    public static Optional<RoleDefinition> find(String role) {
        return Optional.ofNullable(BY_ROLE.get(canonicalize(role)));
    }

    public static boolean isCanonical(String role) {
        String normalized = normalize(role);
        return !normalized.isBlank() && BY_ROLE.containsKey(normalized);
    }

    public static String canonicalize(String role) {
        String normalized = normalize(role);
        return ALIASES.getOrDefault(normalized, normalized);
    }

    public static String label(String role) {
        return find(role).map(RoleDefinition::name).orElse("");
    }

    private static RoleDefinition role(
        String role,
        String name,
        String responsibility,
        String entries,
        String actions,
        String dataScope
    ) {
        return new RoleDefinition(
            role,
            name,
            responsibility,
            List.of(entries.split("、")),
            List.of(actions.split("、")),
            dataScope
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record RoleDefinition(
        String role,
        String name,
        String responsibility,
        List<String> entries,
        List<String> actions,
        String dataScope
    ) {}
}

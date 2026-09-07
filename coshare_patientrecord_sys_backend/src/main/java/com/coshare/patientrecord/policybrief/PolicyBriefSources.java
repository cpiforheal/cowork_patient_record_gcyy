package com.coshare.patientrecord.policybrief;

import java.util.List;

/**
 * 医政早报信息源配置（一期代码常量，二期再做源管理界面）。
 *
 * <p>解析策略为通用启发式（见 PolicyBriefCollectService.extractListItems）：按"同域链接 + 标题长度 + 日期正则"
 * 从列表页提取条目，不依赖每个站点的精确 CSS 选择器；单源结构变化时优先微调本处的 keywords / url，
 * 单源失败只记日志不影响其他源。
 */
public final class PolicyBriefSources {

    /** 资讯分类：政策法规 / 医保支付(DIP/DRG) / 肛肠学术 / 行业动态 */
    public static final String CATEGORY_POLICY = "POLICY";
    public static final String CATEGORY_DIP = "DIP";
    public static final String CATEGORY_ANORECTAL = "ANORECTAL";
    public static final String CATEGORY_GENERAL = "GENERAL";

    public record SourceSpec(
        String name,
        String category,
        String listUrl,
        /** 标题需包含任一关键词才入库；为空表示该源全部接受 */
        List<String> keywords,
        int maxItems
    ) {}

    private PolicyBriefSources() {}

    public static List<SourceSpec> all() {
        return List.of(
            new SourceSpec(
                "国家卫健委 · 政策文件",
                CATEGORY_POLICY,
                "http://www.nhc.gov.cn/wjw/zcwj/list.shtml",
                List.of(),
                10
            ),
            new SourceSpec(
                "国家医保局 · 政策法规",
                CATEGORY_DIP,
                "https://www.nhsa.gov.cn/col/col20/index.html",
                List.of(),
                10
            ),
            new SourceSpec(
                "国家医保局 · DRG/DIP 支付方式改革",
                CATEGORY_DIP,
                "https://www.nhsa.gov.cn/col/col892/index.html",
                List.of("DIP", "DRG", "支付", "结算", "医保"),
                8
            ),
            new SourceSpec(
                "河南省医疗保障局 · 政务动态",
                CATEGORY_DIP,
                "https://ylbz.henan.gov.cn/zwdt/index.html",
                List.of("医保", "DIP", "DRG", "支付", "报销", "集采"),
                8
            ),
            new SourceSpec(
                "河南省卫生健康委 · 新闻动态",
                CATEGORY_POLICY,
                "https://wsjkw.henan.gov.cn/2021/05-27/xwzx.shtml",
                List.of("医疗", "医院", "卫生", "诊疗", "规范", "质控"),
                8
            ),
            new SourceSpec(
                "肛肠学术动态",
                CATEGORY_ANORECTAL,
                "https://www.gcjxzz.com/CN/volumn/home.shtml",
                List.of("肛", "痔", "瘘", "直肠"),
                8
            )
        );
    }
}

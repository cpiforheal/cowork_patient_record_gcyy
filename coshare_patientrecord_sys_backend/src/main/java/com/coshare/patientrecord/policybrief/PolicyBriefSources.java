package com.coshare.patientrecord.policybrief;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 医政早报信息源配置（一期代码常量，二期再做源管理界面）。
 *
 * <p>两类源：① 政府/机构列表页直连（通用启发式解析：同域链接 + 标题长度 + 日期正则）；
 * ② Bing 搜索 RSS（format=rss，服务会自动识别 XML 分支解析 item/title/link/pubDate），
 * 用于覆盖无 RSS、反爬（如卫健委 412）或已下线的目标站点。源 2026-09-07 全部实测可达。
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

    private static String bingRss(String query) {
        return "https://cn.bing.com/search?format=rss&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
    }

    public static List<SourceSpec> all() {
        return List.of(
            new SourceSpec(
                "国家医保局 · 政策法规",
                CATEGORY_DIP,
                "https://www.nhsa.gov.cn/col/col20/index.html",
                List.of(),
                10
            ),
            new SourceSpec(
                "河南省卫健委 · 要闻动态",
                CATEGORY_POLICY,
                "https://wsjkw.henan.gov.cn/ywdt/",
                List.of("医疗", "卫生", "医院", "诊疗", "质控", "公卫", "护理"),
                8
            ),
            new SourceSpec(
                "肛肠学术动态",
                CATEGORY_ANORECTAL,
                bingRss("肛肠 OR 痔疮 OR 肛瘘 指南 OR 共识 OR 研究"),
                List.of("肛", "痔", "瘘", "直肠"),
                8
            ),
            new SourceSpec(
                "DIP 支付改革动态",
                CATEGORY_DIP,
                bingRss("DIP 支付方式改革 医保"),
                List.of("DIP", "DRG", "医保", "支付", "结算"),
                8
            ),
            new SourceSpec(
                "医政政策动态",
                CATEGORY_POLICY,
                bingRss("卫健委 政策文件 医疗 规范"),
                List.of("卫生", "医疗", "政策", "规范", "通知", "委"),
                8
            )
        );
    }
}

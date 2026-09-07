package com.coshare.patientrecord.policybrief;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 医政早报信息源配置（一期代码常量，二期再做源管理界面）。
 *
 * <p>当前策略：以点带面，先聚焦 DIP 付费专区（国家医保局直连 + Bing 搜索 RSS 覆盖主流新闻网），
 * 后续再逐步放开政策/肛肠等其他专区。
 *
 * <p>两类源：① 政府/机构列表页直连（通用启发式解析：同域链接 + 标题长度 + 日期正则）；
 * ② Bing 搜索 RSS（format=rss，服务自动识别 XML 分支解析 item/title/link/pubDate），
 * 支持 site: 限定主流新闻网。源 2026-09-07 实测可达。
 */
public final class PolicyBriefSources {

    /** 资讯分类：政策法规 / 医保支付(DIP/DRG) / 肛肠学术 / 行业动态 */
    public static final String CATEGORY_POLICY = "POLICY";
    public static final String CATEGORY_DIP = "DIP";
    public static final String CATEGORY_ANORECTAL = "ANORECTAL";
    public static final String CATEGORY_GENERAL = "GENERAL";

    /** UGC/词条类站点黑名单：内容噪音大且正文抓取普遍 403，解析阶段直接跳过不入库 */
    public static final List<String> DOMAIN_BLACKLIST = List.of(
        "baike.baidu.com", "zhihu.com", "zhuanlan.zhihu.com", "baijiahao.baidu.com", "zhidao.baidu.com",
        "wenku.baidu.com", "360doc.com", "docin.com", "doc88.com", "csdn.net", "jianshu.com", "sogou.com"
    );

    /** 标题噪音词：命中即跳过（电子封装歧义、词条解释类） */
    public static final List<String> TITLE_NOISE_WORDS = List.of("百科", "SMT", "封装", "双列直插", "是什么意思");

    public record SourceSpec(
        String name,
        String category,
        String listUrl,
        /** 标题需包含任一关键词才入库；为空表示该源全部接受 */
        List<String> keywords,
        /** 标题必须同时包含的全部关键词（如 DIP 源必须含 "DIP"）；为空表示无强制 */
        List<String> requireAll,
        int maxItems
    ) {

        /** 兼容无必含词的源定义 */
        public SourceSpec(String name, String category, String listUrl, List<String> keywords, int maxItems) {
            this(name, category, listUrl, keywords, List.of(), maxItems);
        }
    }

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
                "DIP 付费动态（全网）",
                CATEGORY_DIP,
                bingRss("DIP 医保 支付方式改革 -百科 -知乎 -知道 -文库"),
                List.of("医保", "支付", "结算", "病种", "分组", "DRG", "预算", "付费"),
                List.of("DIP"),
                10
            ),
            new SourceSpec(
                "人民网健康 · DIP",
                CATEGORY_DIP,
                bingRss("DIP OR DRG OR 医保支付 site:health.people.com.cn"),
                List.of(),
                6
            ),
            new SourceSpec(
                "新华网 · DIP",
                CATEGORY_DIP,
                bingRss("DIP OR DRG 医保 site:xinhuanet.com"),
                List.of(),
                6
            ),
            new SourceSpec(
                "中新网 · DIP",
                CATEGORY_DIP,
                bingRss("DIP OR DRG 医保 site:chinanews.com.cn"),
                List.of(),
                6
            ),
            new SourceSpec(
                "健康报 · DIP",
                CATEGORY_DIP,
                bingRss("DIP OR DRG OR 医保支付 site:jkb.com.cn"),
                List.of(),
                6
            ),
            new SourceSpec(
                "光明网 · DIP",
                CATEGORY_DIP,
                bingRss("DIP OR DRG 医保 site:gmw.cn"),
                List.of(),
                6
            )
        );
    }
}

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

    /** 资讯分类：政策法规 / 医保支付(DIP/DRG) / 肛肠学术 / 热点(微博头条) / 行业动态 */
    public static final String CATEGORY_POLICY = "POLICY";
    public static final String CATEGORY_DIP = "DIP";
    public static final String CATEGORY_ANORECTAL = "ANORECTAL";
    public static final String CATEGORY_HOT = "HOT";
    public static final String CATEGORY_GENERAL = "GENERAL";

    /** UGC/词条/词典类站点黑名单：内容噪音大且正文抓取普遍 403，解析阶段直接跳过不入库 */
    public static final List<String> DOMAIN_BLACKLIST = List.of(
        "baidu.com", "zhihu.com", "zhuanlan.zhihu.com", "360doc.com", "docin.com", "doc88.com", "csdn.net",
        "jianshu.com", "sogou.com", "cambridge.org", "iciba.com", "youdao.com", "dict.cn", "bing.com/search"
    );

    /** 标题噪音词：命中即跳过（电子封装歧义、词条解释、词典/导航类） */
    public static final List<String> TITLE_NOISE_WORDS = List.of(
        "百科", "SMT", "封装", "双列直插", "是什么意思", "词典", "翻译", "搜索", "官网", "下载"
    );

    /** 医疗健康热点关键词：微博/头条热榜条目需命中其一才进入热点板块 */
    public static final List<String> HOT_MEDICAL_KEYWORDS = List.of(
        "医疗", "医院", "医生", "患者", "疾病", "健康", "卫生", "医保", "药", "疫苗", "手术",
        "护士", "门诊", "癌", "病毒", "感染", "流行", "DRG", "DIP", "肛肠", "痔", "诊疗", "病历"
    );

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
            ),
            new SourceSpec(
                "39健康 · 肛肠科普",
                CATEGORY_ANORECTAL,
                bingRss("肛肠 OR 痔疮 OR 肛瘘 科普 OR 治疗 site:39.net"),
                List.of(),
                6
            ),
            new SourceSpec(
                "家庭医生在线 · 肛肠科普",
                CATEGORY_ANORECTAL,
                bingRss("肛肠 OR 痔疮 科普 site:familydoctor.com.cn"),
                List.of(),
                6
            )
        );
    }
}

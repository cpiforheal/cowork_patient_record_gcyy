package com.coshare.patientrecord.healtharchive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * 把健康管理档案 7 节内容以 OOXML 追加进 AI 病历 DOCX 的正文末尾。
 * AI 病历原有内容零改动：仅重写 word/document.xml 的 body 尾部，其余 zip 条目字节保真。
 */
@Component
public class HealthArchiveDocxRenderer {

    private static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private final ObjectMapper objectMapper;

    public HealthArchiveDocxRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public byte[] render(byte[] sourceDocx, String archiveNo, String archiveDate, JsonNode auto, JsonNode form)
        throws IOException {
        try {
            Map<String, byte[]> entries = readZip(sourceDocx);
            byte[] documentXml = entries.remove("word/document.xml");
            if (documentXml == null) throw new IOException("DOCX 缺少 word/document.xml");

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(documentXml));
            doc.normalizeDocument();
            Element body = (Element) doc.getElementsByTagNameNS(W_NS, "body").item(0);
            if (body == null) throw new IOException("DOCX 缺少 w:body");
            Node sectPr = firstDirectChild(body, "sectPr");

            List<Element> appended = new ArrayList<>();
            appended.add(pageBreak(doc));
            appended.add(heading(doc, "固始中医肛肠医院健康管理档案登记表", 32, true));
            appended.add(para(doc, "档案编号", blankToDash(archiveNo), "建档日期", blankToDash(archiveDate)));

            appended.add(heading(doc, "一、基本信息（诊前建档）", 24, true));
            appended.add(para(doc, "姓名", text(auto, "name"), "性别", text(auto, "gender")));
            appended.add(para(doc, "年龄", text(auto, "age"), "联系电话", text(auto, "phone")));
            appended.add(para(doc, "家庭住址", text(auto, "address"), "医保类型", text(auto, "insurance")));
            appended.add(checkLineSingle(doc, "客源渠道", form, "sourceChannel", "sourceChannelsOther",
                List.of("自主到院", "亲友转介绍", "外院转诊", "线上短视频", "社区筛查", "术后复诊")));
            appended.add(checkLineSingle(doc, "就诊动因", form, "visitMotivation", "visitMotivationsOther",
                List.of("急症", "肠道筛查(40岁以上)", "术后复查", "调理")));
            appended.add(para(doc, "既往病史/过敏史", text(form, "pastHistory"), "", ""));

            appended.add(heading(doc, "二、诊中辨证（专科检查与诊疗方案）", 24, true));
            JsonNode exam = form.path("specialExam");
            appended.add(para(doc, "专科检查", "肛门视诊：" + orDash(text(exam, "anusVisual"))
                + "；直肠指诊：" + orDash(text(exam, "digitalRectal"))
                + "；肛门镜：" + orDash(text(exam, "anoscope"))
                + "；阳性体征：" + orDash(text(exam, "positiveSigns")), "", ""));
            appended.add(checkLine(doc, "中医体质/证型", form, "tcmConstitution", "tcmConstitutionOther",
                List.of("湿热下注", "脾虚气陷", "气滞血瘀", "血虚", "阳虚", "阴虚")));
            appended.add(para(doc, "人群分类", crowdLabel(text(form, "crowdCategory")), "", ""));
            appended.add(para(doc, "西医诊断", text(auto, "westernDx"), "中医诊断", text(auto, "tcmDx")));
            appended.add(checkLineSingle(doc, "诊疗路径", form, "treatmentPath", "",
                List.of("微创手术治疗", "中医保守治疗(中药内服/坐浴熏洗/艾灸/穴位理疗)")));
            String surgeryDate = text(form, "surgeryDate");
            appended.add(para(doc, "手术日期", surgeryDate.isBlank() ? "待医生补充" : surgeryDate, "", ""));
            appended.add(checkLine(doc, "个性化干预方案", form, "interventions", "",
                List.of("排便管理", "饮食管理", "生活运动(提肛训练)", "中医特色(坐浴/艾灸/穴位/食疗)")));

            appended.add(heading(doc, "三、院内康复", 24, true));
            appended.add(table(doc, List.of("时间节点", "创面/渗血", "疼痛评分", "排便情况", "水肿消退", "用药/坐浴", "提肛训练", "备注"),
                rowsOf(form.path("recoveryRows"), 8)));

            appended.add(heading(doc, "四、心理疏导", 24, true));
            appended.add(checkLine(doc, "主要情绪问题", form, "emotionIssues", "emotionOther",
                List.of("隐私羞怯", "手术恐惧", "疼痛焦虑", "认知误区", "康复担忧")));
            appended.add(checkLine(doc, "干预节点", form, "psychInterventions", "",
                List.of("初诊", "术前", "换药", "出院", "回访")));
            appended.add(para(doc, "疏导记录", text(form, "counselingRecord"), "", ""));

            appended.add(heading(doc, "五、标准化宣教", 24, true));
            appended.add(checkLine(doc, "宣教执行", form, "educationItems", "",
                List.of("门诊接诊话术", "术前告知话术", "出院宣教话术", "回访二次宣教(居家养护/忌口/复查)")));
            String understood = text(form, "patientUnderstood");
            appended.add(para(doc, "患者是否理解", understood.isBlank() ? "□是 □否" : ("☑" + understood), "", ""));

            appended.add(heading(doc, "六、分级随访", 24, true));
            appended.add(table(doc, List.of("随访时间", "随访方式", "创面/恢复", "用药依从", "饮食忌口", "按期复查", "患者反馈", "随访人"),
                rowsOf(form.path("followUpRows"), 8)));

            appended.add(heading(doc, "七、方案调整记录与签字", 24, true));
            appended.add(para(doc, "方案调整记录", text(form, "adjustmentRecord"), "", ""));
            appended.add(para(doc, "建档人", text(form, "signFiledBy"), "主诊医师", text(form, "signAttending")));
            appended.add(para(doc, "质控审核", text(form, "signQc"), "", ""));

            Node cursor = sectPr;
            for (int i = appended.size() - 1; i >= 0; i--) {
                Node node = appended.get(i);
                body.insertBefore(node, cursor);
                cursor = node;
            }

            byte[] updatedXml = toBytes(doc);
            entries.put("word/document.xml", updatedXml);
            return writeZip(entries);
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("健康管理档案渲染失败：" + error.getMessage(), error);
        }
    }

    // ---------- zip ----------

    private Map<String, byte[]> readZip(byte[] data) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int read;
                while ((read = zip.read(buffer)) > 0) out.write(buffer, 0, read);
                entries.put(entry.getName(), out.toByteArray());
            }
        }
        return entries;
    }

    private byte[] writeZip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    // ---------- dom helpers ----------

    private Element el(Document doc, String name) {
        return doc.createElementNS(W_NS, name);
    }

    private Element run(Document doc, String text, boolean bold, int halfPointSize) {
        Element r = el(doc, "w:r");
        Element rPr = el(doc, "w:rPr");
        if (bold) rPr.appendChild(el(doc, "w:b"));
        Element sz = el(doc, "w:sz");
        sz.setAttribute("w:val", String.valueOf(halfPointSize));
        rPr.appendChild(sz);
        r.appendChild(rPr);
        Element t = el(doc, "w:t");
        t.setAttribute("xml:space", "preserve");
        t.setTextContent(text == null ? "" : text);
        r.appendChild(t);
        return r;
    }

    private Element para(Document doc, List<Element> runs) {
        Element p = el(doc, "w:p");
        Element spacing = el(doc, "w:pPr");
        Element space = el(doc, "w:spacing");
        space.setAttribute("w:after", "80");
        spacing.appendChild(space);
        p.appendChild(spacing);
        runs.forEach(p::appendChild);
        return p;
    }

    private Element heading(Document doc, String text, int halfPointSize, boolean bold) {
        return para(doc, List.of(run(doc, text, bold, halfPointSize)));
    }

    private Element para(Document doc, String label, String value, String label2, String value2) {
        List<Element> runs = new ArrayList<>();
        if (label != null && !label.isBlank()) {
            runs.add(run(doc, label + "：", true, 21));
            runs.add(run(doc, blankToDash(value), false, 21));
        }
        if (label2 != null && !label2.isBlank()) {
            runs.add(run(doc, "　　" + label2 + "：", true, 21));
            runs.add(run(doc, blankToDash(value2), false, 21));
        }
        if (runs.isEmpty()) runs.add(run(doc, "", false, 21));
        return para(doc, runs);
    }

    /** 单选下拉的复选态渲染：字段为字符串，命中项 ☑，其余 □；兼容历史数组字段。 */
    private Element checkLineSingle(Document doc, String label, JsonNode form, String field, String otherField,
                                    List<String> options) {
        JsonNode value = form.path(field);
        String selected = value.asText("");
        List<String> selectedList = new ArrayList<>();
        if (value.isArray()) {
            value.forEach(item -> selectedList.add(item.asText("")));
        } else if (!selected.isBlank()) {
            selectedList.add(selected);
        }
        ObjectNode wrapper = objectMapper.createObjectNode();
        wrapper.set("__selected", objectMapper.valueToTree(selectedList));
        return checkLine(doc, label, wrapper, "__selected", otherField, options);
    }

    private Element checkLine(Document doc, String label, JsonNode form, String arrayField, String otherField,
                              List<String> options) {
        List<String> selected = new ArrayList<>();
        JsonNode array = form.path(arrayField);
        if (array.isArray()) {
            array.forEach(item -> selected.add(item.asText("")));
        }
        StringBuilder line = new StringBuilder(label + "：");
        for (String option : options) {
            line.append(selected.contains(option) ? "☑" : "□").append(option).append("　");
        }
        String other = otherField == null || otherField.isBlank() ? "" : text(form, otherField);
        if (!other.isBlank()) {
            line.append("☑其他：").append(other);
        } else if (!otherField.isBlank()) {
            line.append("□其他：______");
        }
        return para(doc, List.of(run(doc, line.toString(), false, 21)));
    }

    private String crowdLabel(String code) {
        if (code == null || code.isBlank()) return "□A类(手术) □B类(慢病高危) □C类(亚健康调理)";
        return switch (code) {
            case "A" -> "☑A类(手术) □B类(慢病高危) □C类(亚健康调理)";
            case "B" -> "□A类(手术) ☑B类(慢病高危) □C类(亚健康调理)";
            case "C" -> "□A类(手术) □B类(慢病高危) ☑C类(亚健康调理)";
            default -> code;
        };
    }

    private List<List<String>> rowsOf(JsonNode rowsNode, int columnCount) {
        List<List<String>> rows = new ArrayList<>();
        if (rowsNode.isArray()) {
            rowsNode.forEach(row -> {
                List<String> cells = new ArrayList<>();
                if (row.isObject()) {
                    row.fields().forEachRemaining(field -> cells.add(field.getValue().asText("")));
                } else if (row.isArray()) {
                    row.forEach(cell -> cells.add(cell.asText("")));
                }
                while (cells.size() < columnCount) cells.add("");
                rows.add(new ArrayList<>(cells.subList(0, columnCount)));
            });
        }
        return rows;
    }

    private Element table(Document doc, List<String> headers, List<List<String>> rows) {
        Element tbl = el(doc, "w:tbl");
        Element tblPr = el(doc, "w:tblPr");
        Element tblW = el(doc, "w:tblW");
        tblW.setAttribute("w:w", "5000");
        tblW.setAttribute("w:type", "pct");
        tblPr.appendChild(tblW);
        Element borders = el(doc, "w:tblBorders");
        for (String edge : List.of("top", "left", "bottom", "right", "insideH", "insideV")) {
            Element border = el(doc, "w:" + edge);
            border.setAttribute("w:val", "single");
            border.setAttribute("w:sz", "4");
            border.setAttribute("w:color", "auto");
            borders.appendChild(border);
        }
        tblPr.appendChild(borders);
        tbl.appendChild(tblPr);

        Element headerRow = tableRow(doc, headers, true);
        tbl.appendChild(headerRow);
        for (List<String> row : rows) {
            tbl.appendChild(tableRow(doc, row, false));
        }
        return tbl;
    }

    private Element tableRow(Document doc, List<String> cells, boolean header) {
        Element tr = el(doc, "w:tr");
        for (String cell : cells) {
            Element tc = el(doc, "w:tc");
            Element tcPr = el(doc, "w:tcPr");
            Element tcW = el(doc, "w:tcW");
            tcW.setAttribute("w:w", String.valueOf(5000 / Math.max(1, cells.size())));
            tcW.setAttribute("w:type", "pct");
            tcPr.appendChild(tcW);
            tc.appendChild(tcPr);
            Element p = el(doc, "w:p");
            p.appendChild(run(doc, cell == null || cell.isBlank() ? "" : cell, header, header ? 18 : 18));
            tc.appendChild(p);
            tr.appendChild(tc);
        }
        return tr;
    }

    private Element pageBreak(Document doc) {
        Element p = el(doc, "w:p");
        Element r = el(doc, "w:r");
        Element br = el(doc, "w:br");
        br.setAttribute("w:type", "page");
        r.appendChild(br);
        p.appendChild(r);
        return p;
    }

    private Node firstDirectChild(Element parent, String localName) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE && localName.equals(node.getLocalName())) return node;
        }
        return null;
    }

    private byte[] toBytes(Document doc) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.STANDALONE, "yes");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(doc), new StreamResult(out));
        return out.toByteArray();
    }

    private String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("").trim();
    }

    private String orDash(String value) {
        return value == null || value.isBlank() ? "待医生补充" : value;
    }

    private String blankToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}

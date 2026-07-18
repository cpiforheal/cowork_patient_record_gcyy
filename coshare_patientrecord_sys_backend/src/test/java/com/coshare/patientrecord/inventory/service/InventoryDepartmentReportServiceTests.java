package com.coshare.patientrecord.inventory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.coshare.patientrecord.inventory.repository.InventoryLedgerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InventoryDepartmentReportServiceTests {

    @Test
    void historicalReportUsesLedgerCutoffAndBindsEveryFilterParameter() {
        CapturingLedgerRepository repository = new CapturingLedgerRepository();
        InventoryDepartmentReportService service = new InventoryDepartmentReportService(repository);

        service.query(
            LocalDate.of(2026, 6, 1),
            LocalDate.of(2026, 6, 30),
            List.of("dept-a", "dept-b"),
            "item-a",
            "一次性耗材",
            "EXAMINATION"
        );

        assertEquals(2, repository.calls.size());
        QueryCall summary = repository.calls.get(0);
        assertEquals(questionMarks(summary.sql()), summary.args().length);
        assertTrue(summary.sql().contains("closingQuantity"));
        assertTrue(summary.sql().contains("m.occurred_at < ?"));
        assertTrue(!summary.sql().contains("current_balance"));
        assertTrue(summary.sql().contains("c.trigger_stage = ?"));
        assertTrue(summary.sql().contains("m.department_id IN (?,?)"));
        assertEquals(LocalDate.of(2026, 7, 1), summary.args()[summary.args().length - 5]);

        QueryCall details = repository.calls.get(1);
        assertEquals(questionMarks(details.sql()), details.args().length);
        assertTrue(details.sql().contains("e.trigger_stage = ?"));
        assertTrue(List.of(details.args()).contains("EXAMINATION"));
    }

    @Test
    void pdfAndXlsxAreRenderedFromTheSameReportModel() {
        CapturingLedgerRepository repository = new CapturingLedgerRepository();
        InventoryDepartmentReportService service = new InventoryDepartmentReportService(repository);
        ObjectNode report = repository.mapper().createObjectNode();
        report.put("from", "2026-06-01");
        report.put("to", "2026-06-30");
        ObjectNode summary = report.putArray("summary").addObject();
        summary.put("departmentId", "dept-a");
        summary.put("department", "测试科室");
        summary.put("itemName", "纱布");
        summary.put("unit", "包");
        summary.put("openingQuantity", 10);
        summary.put("transferInQuantity", 4);
        summary.put("returnQuantity", 1);
        summary.put("consumedQuantity", 3);
        summary.put("reversalQuantity", 1);
        summary.put("scrapQuantity", 0);
        summary.put("adjustmentQuantity", 0);
        summary.put("closingQuantity", 11);
        ObjectNode detail = report.putArray("details").addObject();
        detail.put("departmentId", "dept-a");
        detail.put("visitDate", "2026-06-12");
        detail.put("encounterId", "enc-001");
        detail.put("patientMasked", "张**");
        detail.put("triggerStage", "EXAMINATION");
        detail.put("packageVersion", 2);
        detail.put("itemName", "纱布");
        detail.put("batchNo", "B001");
        detail.put("quantity", 3);

        byte[] xlsx = service.exportXlsx(report);
        byte[] pdf = service.exportPdf(report);

        assertTrue(xlsx.length > 200);
        assertEquals('P', xlsx[0]);
        assertEquals('K', xlsx[1]);
        assertTrue(pdf.length > 200);
        assertEquals('%', pdf[0]);
        assertEquals('P', pdf[1]);
    }

    private static int questionMarks(String sql) {
        return (int) sql.chars().filter(value -> value == '?').count();
    }

    private static final class CapturingLedgerRepository extends InventoryLedgerRepository {
        private final List<QueryCall> calls = new ArrayList<>();

        private CapturingLedgerRepository() {
            super(null, new ObjectMapper());
        }

        @Override
        public ArrayNode queryJson(String sql, Object... args) {
            calls.add(new QueryCall(sql, args));
            return mapper().createArrayNode();
        }
    }

    private record QueryCall(String sql, Object[] args) {}
}

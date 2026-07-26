package com.coshare.patientrecord.inventory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class InventoryWeeklyServiceTests {

    private final InventoryWeeklyService service = new InventoryWeeklyService(null, null);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void rejectsMalformedWeekBeforeDatabaseAccess() {
        var payload = mapper.createObjectNode();
        payload.put("name", "门诊周度标准");
        payload.put("effectiveWeek", "2026-01");
        payload.putArray("lines").addObject();

        assertThrows(IllegalArgumentException.class, () -> service.saveStandard(payload, null));
    }

    @Test
    void rejectsEmptyStandardLines() {
        var payload = mapper.createObjectNode();
        payload.put("name", "门诊周度标准");
        payload.put("effectiveWeek", "2026-W30");

        assertThrows(IllegalArgumentException.class, () -> service.saveStandard(payload, null));
    }

    @Test
    void normalizesSupportedCareTypes() throws Exception {
        Method normalize = InventoryWeeklyService.class.getDeclaredMethod("normalizeCareType", String.class);
        normalize.setAccessible(true);

        assertEquals("outpatient", invoke(normalize, "门诊"));
        assertEquals("outpatient", invoke(normalize, "OUTPATIENT"));
        assertEquals("inpatient", invoke(normalize, "住院"));
        assertEquals("inpatient", invoke(normalize, "inpatient"));
        assertThrows(IllegalArgumentException.class, () -> invoke(normalize, "emergency"));
    }

    private static String invoke(Method method, String value) throws Exception {
        try {
            return (String) method.invoke(null, value);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw error;
        }
    }
}

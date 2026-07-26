package com.coshare.patientrecord.maintenance.datapurge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DataPurgeMaintenanceFilterTest {

    @Test
    void lockRejectsOtherWritesButKeepsReadsAvailable() throws Exception {
        DataPurgeMaintenanceState state = new DataPurgeMaintenanceState();
        state.tryLock();
        DataPurgeMaintenanceFilter filter = new DataPurgeMaintenanceFilter(state);

        MockHttpServletRequest write = new MockHttpServletRequest("POST", "/inventory-api/requests");
        MockHttpServletResponse writeResponse = new MockHttpServletResponse();
        FilterChain writeChain = mock(FilterChain.class);
        filter.doFilter(write, writeResponse, writeChain);
        assertThat(writeResponse.getStatus()).isEqualTo(503);
        verifyNoInteractions(writeChain);

        MockHttpServletRequest read = new MockHttpServletRequest("GET", "/clinic-api/health");
        MockHttpServletResponse readResponse = new MockHttpServletResponse();
        FilterChain readChain = mock(FilterChain.class);
        filter.doFilter(read, readResponse, readChain);
        verify(readChain).doFilter(read, readResponse);

        MockHttpServletRequest purge = new MockHttpServletRequest("POST", "/clinic-api/maintenance/data-purge/runs/run-1/resume-files");
        MockHttpServletResponse purgeResponse = new MockHttpServletResponse();
        FilterChain purgeChain = mock(FilterChain.class);
        filter.doFilter(purge, purgeResponse, purgeChain);
        verify(purgeChain).doFilter(purge, purgeResponse);
    }
}

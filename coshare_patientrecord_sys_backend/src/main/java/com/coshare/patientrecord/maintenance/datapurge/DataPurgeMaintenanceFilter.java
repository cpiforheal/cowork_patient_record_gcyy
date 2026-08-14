package com.coshare.patientrecord.maintenance.datapurge;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Profile("mysql")
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class DataPurgeMaintenanceFilter extends OncePerRequestFilter {

    private final DataPurgeMaintenanceState state;

    public DataPurgeMaintenanceFilter(DataPurgeMaintenanceState state) {
        this.state = state;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        boolean readOnly = HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method);
        boolean purgeEndpoint = uri != null && uri.startsWith("/clinic-api/maintenance/data-purge");
        if (state.isLocked() && !readOnly && !purgeEndpoint) {
            response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value(), "Data maintenance is running; write requests are temporarily disabled");
            return;
        }
        filterChain.doFilter(request, response);
    }
}

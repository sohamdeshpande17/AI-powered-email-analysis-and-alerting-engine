package com.meridian.circular.web;

import com.meridian.circular.domain.AppUser;
import com.meridian.circular.security.BearerAuthInterceptor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Logs every API request/response and threads a trace id through all log lines
 * for that request via the SLF4J {@link MDC} (rendered by the {@code %X{traceId}}
 * pattern in application.yml).
 *
 * <p>The trace id is taken from an inbound {@code X-Trace-Id} header when present
 * — this lets a circular's trace id from the scraper / AI engine carry into the
 * backend logs — otherwise a fresh short id is generated. It is echoed back on
 * the response so callers can correlate too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("api.access");
    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }
        MDC.put(MDC_KEY, traceId);
        response.setHeader(TRACE_HEADER, traceId);

        String uri = request.getRequestURI();
        boolean api = uri.contains("/api/");
        String query = request.getQueryString();
        String path = query == null ? uri : uri + "?" + query;
        long start = System.currentTimeMillis();

        if (api) {
            log.info("→ {} {}", request.getMethod(), path);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (api) {
                long ms = System.currentTimeMillis() - start;
                Object actor = request.getAttribute(BearerAuthInterceptor.ACTOR_ATTRIBUTE);
                String who = actor instanceof AppUser u ? u.email : "anonymous";
                log.info("← {} {} {} ({}ms) actor={}",
                        request.getMethod(), uri, response.getStatus(), ms, who);
            }
            MDC.remove(MDC_KEY);
        }
    }
}

package com.finance.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

// v1.1 #5 — single trace ID per request. Sourced from X-Trace-Id if the client
// (or upstream proxy) supplied one; otherwise generated. Stored in MDC so every
// log line in the request carries it, and echoed in the response header so a
// client can correlate its own request log with server-side activity.
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER  = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String inbound = req.getHeader(HEADER);
        String traceId = (inbound != null && !inbound.isBlank())
                ? inbound
                : UUID.randomUUID().toString();
        try {
            MDC.put(MDC_KEY, traceId);
            res.setHeader(HEADER, traceId);
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}

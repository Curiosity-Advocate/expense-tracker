package com.finance.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceIdFilterTest {

    @Mock HttpServletRequest req;
    @Mock HttpServletResponse res;
    @Mock FilterChain chain;

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    // v1.1 #5 — missing X-Trace-Id: the filter generates a UUID, sets it in MDC for
    // the duration of the chain, echoes it on the response, then removes it.
    @Test
    void missingHeader_generatesTraceId_setsMdcAndResponse() throws ServletException, IOException {
        when(req.getHeader("X-Trace-Id")).thenReturn(null);

        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        doAnswer(inv -> {
            mdcDuringChain.set(MDC.get(TraceIdFilter.MDC_KEY));
            return null;
        }).when(chain).doFilter(req, res);

        filter.doFilter(req, res, chain);

        // MDC was populated during the chain — generated, non-null, looks like a UUID
        assertThat(mdcDuringChain.get()).isNotNull().hasSize(36);

        // The generated ID was echoed on the response under the same name
        ArgumentCaptor<String> resHeader = ArgumentCaptor.forClass(String.class);
        verify(res).setHeader(eq("X-Trace-Id"), resHeader.capture());
        assertThat(resHeader.getValue()).isEqualTo(mdcDuringChain.get());

        // MDC is cleared after the filter completes
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    // v1.1 #5 — caller-supplied trace ID: the filter respects it, propagates it to
    // MDC and echoes it on the response.
    @Test
    void existingHeader_propagatesToMdcAndResponse() throws ServletException, IOException {
        String supplied = "incoming-trace-id-abc";
        when(req.getHeader("X-Trace-Id")).thenReturn(supplied);

        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        doAnswer(inv -> {
            mdcDuringChain.set(MDC.get(TraceIdFilter.MDC_KEY));
            return null;
        }).when(chain).doFilter(req, res);

        filter.doFilter(req, res, chain);

        assertThat(mdcDuringChain.get()).isEqualTo(supplied);
        verify(res).setHeader("X-Trace-Id", supplied);
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    // v1.1 #5 — exceptional path: even when the chain throws, MDC must be cleared
    // so the next request reused on this thread does not inherit a stale trace ID.
    @Test
    void chainThrows_mdcIsStillCleared() throws ServletException, IOException {
        when(req.getHeader("X-Trace-Id")).thenReturn(null);
        doAnswer(inv -> { throw new ServletException("boom"); })
                .when(chain).doFilter(req, res);

        try {
            filter.doFilter(req, res, chain);
        } catch (ServletException ignored) {
            // expected
        }

        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }
}

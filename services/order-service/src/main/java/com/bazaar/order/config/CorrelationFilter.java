package com.bazaar.order.config;

import java.io.IOException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Correlation-Id";
    private static final Logger log = LoggerFactory.getLogger(CorrelationFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String correlationId = incoming != null && incoming.matches("[A-Za-z0-9._:-]{8,80}")
                ? incoming
                : UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);
        response.setHeader(HEADER, correlationId);
        SpanContext span = Span.current().getSpanContext();
        boolean traced = span.isValid();
        if (traced) {
            MDC.put("traceId", span.getTraceId());
            MDC.put("spanId", span.getSpanId());
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (!request.getRequestURI().startsWith("/actuator")) {
                SpanContext current = Span.current().getSpanContext();
                if (current.isValid()) {
                    MDC.put("traceId", current.getTraceId());
                    MDC.put("spanId", current.getSpanId());
                }
                log.info("HTTP {} {} status={}", request.getMethod(), route(request.getRequestURI()),
                        response.getStatus());
            }
            MDC.remove("correlationId");
            MDC.remove("traceId");
            MDC.remove("spanId");
        }
    }

    private static String route(String path) {
        return path.replaceAll(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                "{id}");
    }
}

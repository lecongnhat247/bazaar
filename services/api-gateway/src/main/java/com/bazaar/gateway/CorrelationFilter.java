package com.bazaar.gateway;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import io.micrometer.context.ContextRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;

import reactor.core.publisher.Mono;

@Component
public class CorrelationFilter implements WebFilter, Ordered {

    static final String HEADER = "X-Correlation-Id";
    private static final Logger log = LoggerFactory.getLogger(CorrelationFilter.class);
    private static final Pattern TRACEPARENT = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})");

    public CorrelationFilter() {
        try {
            ContextRegistry.getInstance().registerThreadLocalAccessor(
                    "correlationId",
                    () -> MDC.get("correlationId"),
                    value -> MDC.put("correlationId", value),
                    () -> MDC.remove("correlationId"));
        } catch (RuntimeException ignored) {
            // The registry lives for the JVM. A second Spring context reuses the key.
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String correlationId = incoming != null && incoming.matches("[A-Za-z0-9._:-]{8,80}")
                ? incoming
                : UUID.randomUUID().toString();
        SpanContext span = Span.current().getSpanContext();
        String[] incomingTrace = traceparent(exchange.getRequest().getHeaders().getFirst("traceparent"));
        String traceId = span.isValid() ? span.getTraceId() : incomingTrace[0];
        String spanId = span.isValid() ? span.getSpanId() : incomingTrace[1];
        String method = exchange.getRequest().getMethod().name();
        String path = route(exchange.getRequest().getURI().getPath());
        MDC.put("correlationId", correlationId);
        if (traceId != null) {
            MDC.put("traceId", traceId);
            MDC.put("spanId", spanId);
        }
        ServerWebExchange next = exchange.mutate()
                .request(exchange.getRequest().mutate().header(HEADER, correlationId).build())
                .build();
        next.getResponse().getHeaders().set(HEADER, correlationId);
        return chain.filter(next)
                .contextWrite(context -> context.put("correlationId", correlationId))
                .doFinally(signal -> {
                    SpanContext current = Span.current().getSpanContext();
                    String activeTrace = current.isValid() ? current.getTraceId() : traceId;
                    String activeSpan = current.isValid() ? current.getSpanId() : spanId;
                    MDC.put("correlationId", correlationId);
                    if (activeTrace != null) {
                        MDC.put("traceId", activeTrace);
                        MDC.put("spanId", activeSpan);
                    }
                    if (!path.startsWith("/actuator")) {
                        HttpStatusCode status = next.getResponse().getStatusCode();
                        log.info("HTTP {} {} status={}", method, path, status == null ? 0 : status.value());
                    }
                    MDC.remove("correlationId");
                    MDC.remove("traceId");
                    MDC.remove("spanId");
                });
    }

    private static String[] traceparent(String header) {
        if (header == null) {
            return new String[] {null, null};
        }
        Matcher matcher = TRACEPARENT.matcher(header.trim());
        if (!matcher.matches()) {
            return new String[] {null, null};
        }
        return new String[] {matcher.group(1), matcher.group(2)};
    }

    private static String route(String path) {
        return path.replaceAll(
                "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                "{id}");
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}

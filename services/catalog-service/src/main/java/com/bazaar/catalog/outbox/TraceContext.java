package com.bazaar.catalog.outbox;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.MDC;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;

/**
 * Keeps a W3C traceparent with an outbox row so a later publish continues the
 * request that created the business fact. The value is a trace id and a span
 * id, never a customer, order, or payment payload.
 */
public final class TraceContext implements AutoCloseable {

    private static final Pattern W3C = Pattern.compile("00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})");
    private static final ThreadLocal<String> HELD = new ThreadLocal<>();

    private final Scope scope;
    private final String previousTraceId;
    private final String previousSpanId;
    private final String previousHeld;

    private TraceContext(Scope scope, String previousTraceId, String previousSpanId, String previousHeld) {
        this.scope = scope;
        this.previousTraceId = previousTraceId;
        this.previousSpanId = previousSpanId;
        this.previousHeld = previousHeld;
    }

    public static String current() {
        String held = HELD.get();
        if (held != null) {
            return held;
        }
        SpanContext context = Span.current().getSpanContext();
        if (!context.isValid()) {
            return null;
        }
        String flags = context.isSampled() ? "01" : "00";
        return "00-" + context.getTraceId() + "-" + context.getSpanId() + "-" + flags;
    }

    public static TraceContext open(String traceparent) {
        SpanContext remote = remote(traceparent);
        if (remote == null) {
            return new TraceContext(null, null, null, null);
        }
        String previousTraceId = MDC.get("traceId");
        String previousSpanId = MDC.get("spanId");
        String previousHeld = HELD.get();
        MDC.put("traceId", remote.getTraceId());
        MDC.put("spanId", remote.getSpanId());
        HELD.set(traceparent.trim());
        return new TraceContext(Span.wrap(remote).makeCurrent(), previousTraceId, previousSpanId, previousHeld);
    }

    @Override
    public void close() {
        if (scope == null) {
            return;
        }
        restore("traceId", previousTraceId);
        restore("spanId", previousSpanId);
        if (previousHeld == null) {
            HELD.remove();
        } else {
            HELD.set(previousHeld);
        }
        scope.close();
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, previous);
        }
    }

    private static SpanContext remote(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        Matcher matcher = W3C.matcher(traceparent.trim());
        if (!matcher.matches()) {
            return null;
        }
        TraceFlags flags = "01".equals(matcher.group(3)) ? TraceFlags.getSampled() : TraceFlags.getDefault();
        return SpanContext.createFromRemoteParent(matcher.group(1), matcher.group(2), flags, TraceState.getDefault());
    }
}

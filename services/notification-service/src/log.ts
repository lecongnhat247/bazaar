import { AsyncLocalStorage } from "node:async_hooks";

import { LoggerService } from "@nestjs/common";

export type LogContext = {
  traceId?: string;
  spanId?: string;
  correlationId?: string;
  eventId?: string;
};

export const logContext = new AsyncLocalStorage<LogContext>();

const HIDDEN = new Set(["customerId", "authorization", "token", "password", "value"]);

export class JsonLogger implements LoggerService {
  log(message: unknown, context?: string): void {
    write("info", message, context);
  }

  error(message: unknown, context?: string): void {
    write("error", message, context);
  }

  warn(message: unknown, context?: string): void {
    write("warn", message, context);
  }

  debug(message: unknown, context?: string): void {
    write("debug", message, context);
  }

  verbose(message: unknown, context?: string): void {
    write("debug", message, context);
  }
}

function write(severity: string, message: unknown, context?: string): void {
  const parsed = typeof message === "string" && message.startsWith("{") ? parse(message) : undefined;
  const fields = parsed ?? { message };
  const text = typeof fields.message === "string" ? fields.message : String(message);
  const active = logContext.getStore() ?? {};
  const line: Record<string, unknown> = {
    "@timestamp": new Date().toISOString(),
    "log.level": severity,
    message: text,
    "service.name": "notification-service",
    "service.environment": process.env.BAZAAR_ENV ?? "local",
    "log.logger": context ?? "notification-service",
  };
  copy(line, active);
  if (parsed) {
    for (const [key, value] of Object.entries(parsed)) {
      if (key !== "message" && !HIDDEN.has(key)) {
        line[key] = value;
      }
    }
  }
  console.log(JSON.stringify(line));
}

function copy(line: Record<string, unknown>, active: LogContext): void {
  if (active.traceId) {
    line.traceId = active.traceId;
  }
  if (active.spanId) {
    line.spanId = active.spanId;
  }
  if (active.correlationId) {
    line.correlationId = active.correlationId;
  }
  if (active.eventId) {
    line.eventId = active.eventId;
  }
}

function parse(value: string): Record<string, unknown> | undefined {
  try {
    const parsed = JSON.parse(value) as unknown;
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>;
    }
  } catch {
    return undefined;
  }
  return undefined;
}

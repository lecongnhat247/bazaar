import { Controller, Get, Header } from "@nestjs/common";
import { Counter, Gauge, Registry, collectDefaultMetrics } from "prom-client";

export const registry = new Registry();

collectDefaultMetrics({ register: registry });

export const notificationsDelivered = new Counter({
  name: "bazaar_notification_delivered_total",
  help: "Notifications stored for a customer.",
  labelNames: ["type"],
  registers: [registry],
});

export const notificationsDuplicate = new Counter({
  name: "bazaar_notification_duplicate_total",
  help: "Notification events ignored because the event id was already stored.",
  registers: [registry],
});

export const kafkaProcessed = new Counter({
  name: "bazaar_kafka_processed_total",
  help: "Kafka records applied by the notification consumer.",
  registers: [registry],
});

export const kafkaRetries = new Counter({
  name: "bazaar_kafka_retries_total",
  help: "Kafka records the notification consumer will try again.",
  registers: [registry],
});

export const kafkaDlq = new Counter({
  name: "bazaar_kafka_dlq_total",
  help: "Kafka records copied to the dead-letter topic.",
  registers: [registry],
});

export const kafkaLag = new Gauge({
  name: "bazaar_kafka_consumer_lag",
  help: "Maximum lag across the notification consumer partitions.",
  registers: [registry],
});

@Controller()
export class MetricsController {
  @Get("metrics")
  @Header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
  metrics(): Promise<string> {
    return registry.metrics();
  }
}

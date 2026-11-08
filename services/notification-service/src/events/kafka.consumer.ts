import { Injectable, Logger, OnApplicationBootstrap, OnApplicationShutdown } from "@nestjs/common";
import { Admin, Consumer, Kafka, Producer } from "kafkajs";

import { NotificationService } from "../notifications/notification.service";
import { backoffMs, failureAction } from "./dead-letter";

const TOPICS = ["bazaar.order.events", "bazaar.payment.events"];
const GROUP = "notification-service";

@Injectable()
export class KafkaConsumer implements OnApplicationBootstrap, OnApplicationShutdown {
  private readonly log = new Logger(KafkaConsumer.name);
  private readonly attempts = new Map<string, number>();
  private consumer: Consumer | null = null;
  private producer: Producer | null = null;
  private admin: Admin | null = null;
  private lagTimer: NodeJS.Timeout | null = null;
  private stopped = false;

  constructor(private readonly notifications: NotificationService) {}

  onApplicationBootstrap(): void {
    const brokers = process.env.KAFKA_BROKERS;
    if (!brokers) {
      return;
    }
    void this.connect(brokers);
  }

  async onApplicationShutdown(): Promise<void> {
    this.stopped = true;
    if (this.lagTimer) {
      clearInterval(this.lagTimer);
    }
    await this.consumer?.disconnect();
    await this.producer?.disconnect();
    await this.admin?.disconnect();
  }

  private async connect(brokers: string): Promise<void> {
    while (!this.stopped) {
      try {
        const kafka = new Kafka({ clientId: "notification-service", brokers: brokers.split(",") });
        const consumer = kafka.consumer({ groupId: GROUP });
        const producer = kafka.producer();
        const admin = kafka.admin();
        this.consumer = consumer;
        this.producer = producer;
        this.admin = admin;
        await consumer.connect();
        await producer.connect();
        await admin.connect();
        await consumer.subscribe({ topics: TOPICS, fromBeginning: false });
        this.lagTimer = setInterval(() => void this.logLag(), 30_000);
        this.log.log("Kafka consumer started.");
        await consumer.run({
          eachMessage: async ({ topic, partition, message }) => {
            if (!message.value) {
              return;
            }
            const raw = message.value.toString("utf8");
            const key = `${topic}:${partition}:${message.offset}`;
            let body: unknown;
            try {
              body = JSON.parse(raw);
            } catch (error) {
              await this.deadLetter(topic, message.key?.toString() ?? null, "", "", raw, String(error), 1);
              return;
            }
            const eventId = textField(body, "eventId");
            const correlationId = textField(body, "correlationId");
            try {
              await this.notifications.accept(body);
              this.attempts.delete(key);
            } catch (error) {
              const attempt = (this.attempts.get(key) ?? 0) + 1;
              this.attempts.set(key, attempt);
              if (failureAction(attempt, false) === "dead") {
                await this.deadLetter(topic, message.key?.toString() ?? null, eventId, correlationId, raw, String(error), attempt);
                this.attempts.delete(key);
                return;
              }
              this.log.warn(JSON.stringify({
                message: "Notification event will be retried",
                eventId,
                correlationId,
                attempt,
                error: String(error),
              }));
              await sleep(backoffMs(attempt));
              throw error;
            }
          },
        });
        return;
      } catch (error) {
        this.log.warn(JSON.stringify({ message: "Kafka consumer will retry", error: String(error) }));
        if (this.lagTimer) {
          clearInterval(this.lagTimer);
          this.lagTimer = null;
        }
        await sleep(5000);
      }
    }
  }

  private async deadLetter(
    topic: string,
    key: string | null,
    eventId: string,
    correlationId: string,
    value: string,
    error: string,
    attempts: number,
  ): Promise<void> {
    const body = JSON.stringify({
      originalTopic: topic,
      eventId,
      correlationId,
      attempts,
      error,
      value: value.slice(0, 2000),
    });
    try {
      await this.producer?.send({
        topic: process.env.DLQ_TOPIC ?? "bazaar.dlq",
        messages: [{ key, value: body }],
      });
      this.log.error(JSON.stringify({
        message: "Dead letter",
        originalTopic: topic,
        eventId,
        correlationId,
        attempts,
        error,
      }));
    } catch (publishError) {
      this.log.error(JSON.stringify({
        message: "Dead letter was not copied",
        originalTopic: topic,
        eventId,
        correlationId,
        attempts,
        error: String(publishError),
      }));
      throw publishError;
    }
  }

  private async logLag(): Promise<void> {
    if (!this.admin) {
      return;
    }
    try {
      let max = 0;
      const committed = await this.admin.fetchOffsets({ groupId: GROUP, topics: TOPICS });
      for (const topic of committed) {
        const ends = await this.admin.fetchTopicOffsets(topic.topic);
        for (const part of topic.partitions) {
          const end = ends.find((item) => item.partition === part.partition);
          const current = Number(part.offset);
          const high = Number(end?.offset ?? part.offset);
          if (Number.isFinite(current) && Number.isFinite(high)) {
            max = Math.max(max, high - current);
          }
        }
      }
      this.log.log(JSON.stringify({ message: "Kafka consumer lag", group: GROUP, max }));
    } catch (error) {
      this.log.warn(JSON.stringify({ message: "Kafka lag could not be read", error: String(error) }));
    }
  }
}

function textField(body: unknown, name: string): string {
  if (!body || typeof body !== "object" || !(name in body)) {
    return "";
  }
  const value = (body as Record<string, unknown>)[name];
  return typeof value === "string" ? value : "";
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

import { Injectable, Logger, OnApplicationBootstrap, OnApplicationShutdown } from "@nestjs/common";
import { Consumer, Kafka } from "kafkajs";

import { NotificationService } from "../notifications/notification.service";

@Injectable()
export class KafkaConsumer implements OnApplicationBootstrap, OnApplicationShutdown {
  private readonly log = new Logger(KafkaConsumer.name);
  private consumer: Consumer | null = null;
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
    await this.consumer?.disconnect();
  }

  private async connect(brokers: string): Promise<void> {
    while (!this.stopped) {
      try {
        const kafka = new Kafka({ clientId: "notification-service", brokers: brokers.split(",") });
        const consumer = kafka.consumer({ groupId: "notification-service" });
        this.consumer = consumer;
        await consumer.connect();
        await consumer.subscribe({ topics: ["bazaar.order.events", "bazaar.payment.events"], fromBeginning: false });
        await consumer.run({
          eachMessage: async ({ message }) => {
            if (!message.value) {
              return;
            }
            let body: unknown;
            try {
              body = JSON.parse(message.value.toString("utf8"));
            } catch (error) {
              this.log.warn(JSON.stringify({ message: "Skipped a notification event that was not JSON", error: String(error) }));
              return;
            }
            await this.notifications.accept(body);
          },
        });
        this.log.log("Kafka consumer started.");
        return;
      } catch (error) {
        this.log.warn(JSON.stringify({ message: "Kafka consumer will retry", error: String(error) }));
        await new Promise((resolve) => setTimeout(resolve, 5000));
      }
    }
  }
}

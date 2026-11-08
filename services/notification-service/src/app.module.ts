import { Module } from "@nestjs/common";

import { CustomerGuard } from "./auth/customer.guard";
import { TokenVerifier } from "./auth/token-verifier";
import { KafkaConsumer } from "./events/kafka.consumer";
import { HealthController } from "./health.controller";
import { NotificationService } from "./notifications/notification.service";
import { MemoryNotificationStore, NotificationStore, PgNotificationStore } from "./notifications/notification.store";
import { NotificationsController } from "./notifications/notifications.controller";
import { NotificationsGateway } from "./realtime/notifications.gateway";

@Module({
  controllers: [HealthController, NotificationsController],
  providers: [
    TokenVerifier,
    CustomerGuard,
    NotificationsGateway,
    NotificationService,
    KafkaConsumer,
    {
      provide: NotificationStore,
      useFactory: async () => {
        const databaseUrl = process.env.DATABASE_URL;
        if (!databaseUrl) {
          return new MemoryNotificationStore();
        }
        const store = new PgNotificationStore(databaseUrl);
        await store.ensure();
        return store;
      },
    },
  ],
})
export class AppModule {}

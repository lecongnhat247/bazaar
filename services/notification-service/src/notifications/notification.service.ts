import { Injectable, Logger } from "@nestjs/common";

import { toNotice } from "../events/map-event";
import { NotificationsGateway } from "../realtime/notifications.gateway";
import { NotificationStore, StoredNotice } from "./notification.store";

@Injectable()
export class NotificationService {
  private readonly log = new Logger(NotificationService.name);

  constructor(
    private readonly store: NotificationStore,
    private readonly realtime: NotificationsGateway,
  ) {}

  async accept(raw: unknown): Promise<void> {
    const notice = toNotice(raw);
    if (!notice) {
      return;
    }
    const saved = await this.store.save(notice);
    if (!saved) {
      this.log.log(JSON.stringify({ message: "Duplicate notification ignored", eventId: notice.eventId, orderId: notice.orderId }));
      return;
    }
    this.realtime.publish(notice);
    this.log.log(JSON.stringify({
      message: "Stored notification",
      eventId: notice.eventId,
      eventType: notice.type,
      orderId: notice.orderId,
    }));
  }

  list(customerId: string): Promise<StoredNotice[]> {
    return this.store.list(customerId);
  }
}

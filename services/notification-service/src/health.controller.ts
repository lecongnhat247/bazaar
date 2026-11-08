import { Controller, Get, ServiceUnavailableException } from "@nestjs/common";

import { NotificationStore } from "./notifications/notification.store";

@Controller("health")
export class HealthController {
  constructor(private readonly store: NotificationStore) {}

  @Get()
  live() {
    return { status: "UP" };
  }

  @Get("ready")
  async ready() {
    if (!(await this.store.ready())) {
      throw new ServiceUnavailableException({ status: "DOWN" });
    }
    return { status: "UP" };
  }
}

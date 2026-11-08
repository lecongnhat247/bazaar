import { Controller, Get, Req, UseGuards } from "@nestjs/common";

import { CustomerGuard, CustomerRequest } from "../auth/customer.guard";
import { NotificationService } from "./notification.service";

@Controller("api/v1/notifications")
@UseGuards(CustomerGuard)
export class NotificationsController {
  constructor(private readonly notifications: NotificationService) {}

  @Get()
  async list(@Req() request: CustomerRequest) {
    return { items: await this.notifications.list(request.customerId ?? "") };
  }
}

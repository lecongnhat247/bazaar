import { Logger } from "@nestjs/common";
import { OnGatewayConnection, OnGatewayInit, WebSocketGateway, WebSocketServer } from "@nestjs/websockets";
import { createAdapter } from "@socket.io/redis-adapter";
import Redis from "ioredis";
import { Server, Socket } from "socket.io";

import { bearerOrCookie, TokenVerifier } from "../auth/token-verifier";
import { Notice } from "../events/map-event";

@WebSocketGateway({
  cors: {
    origin: (process.env.CORS_ORIGIN ?? "http://localhost:3000").split(","),
    credentials: true,
  },
})
export class NotificationsGateway implements OnGatewayInit, OnGatewayConnection {
  private readonly log = new Logger(NotificationsGateway.name);

  @WebSocketServer()
  server!: Server;

  constructor(private readonly tokens: TokenVerifier) {}

  afterInit(server: Server): void {
    const host = process.env.REDIS_HOST;
    if (!host) {
      return;
    }
    const port = Number(process.env.REDIS_PORT ?? 6379);
    const publisher = new Redis({ host, port, lazyConnect: true, maxRetriesPerRequest: 1, connectTimeout: 300 });
    const subscriber = publisher.duplicate();
    Promise.all([publisher.connect(), subscriber.connect()])
      .then(() => {
        server.adapter(createAdapter(publisher, subscriber));
        this.log.log("Socket.IO is using the Redis adapter.");
      })
      .catch((error: unknown) => {
        this.log.warn(`Socket.IO stayed on the local adapter because Redis did not answer: ${String(error)}`);
      });
  }

  async handleConnection(client: Socket): Promise<void> {
    const token = bearerOrCookie(undefined, client.handshake.headers.cookie, client.handshake.auth?.token);
    const subject = await this.tokens.subject(token);
    if (!subject) {
      client.disconnect(true);
      return;
    }
    await client.join(room(subject));
  }

  publish(notice: Notice): void {
    const { customerId, ...body } = notice;
    this.server.to(room(customerId)).emit("notification", body);
  }
}

function room(customerId: string): string {
  return `customer:${customerId}`;
}

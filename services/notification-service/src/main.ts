import "reflect-metadata";

import { NestFactory } from "@nestjs/core";

import { AppModule } from "./app.module";
import { JsonLogger } from "./log";

async function bootstrap(): Promise<void> {
  const app = await NestFactory.create(AppModule, { logger: new JsonLogger() });
  const origin = (process.env.CORS_ORIGIN ?? "http://localhost:3000").split(",");
  app.enableCors({ origin, credentials: true });
  await app.listen(process.env.PORT ?? 8080);
}

void bootstrap();

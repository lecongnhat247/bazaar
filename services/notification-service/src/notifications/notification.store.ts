import { Pool } from "pg";

import { Notice } from "../events/map-event";

export type StoredNotice = Omit<Notice, "customerId">;

export abstract class NotificationStore {
  abstract save(notice: Notice): Promise<boolean>;
  abstract list(customerId: string): Promise<StoredNotice[]>;
  abstract ready(): Promise<boolean>;
}

export class MemoryNotificationStore extends NotificationStore {
  private readonly rows: Notice[] = [];

  async save(notice: Notice): Promise<boolean> {
    if (this.rows.some((row) => row.eventId === notice.eventId)) {
      return false;
    }
    this.rows.push(notice);
    return true;
  }

  async list(customerId: string): Promise<StoredNotice[]> {
    return this.rows
      .filter((row) => row.customerId === customerId)
      .slice(-50)
      .map(publicNotice);
  }

  async ready(): Promise<boolean> {
    return true;
  }
}

export class PgNotificationStore extends NotificationStore {
  private readonly pool: Pool;

  constructor(databaseUrl: string) {
    super();
    this.pool = new Pool({ connectionString: databaseUrl, max: 4, connectionTimeoutMillis: 2000 });
  }

  async ensure(): Promise<void> {
    await this.pool.query(`
      CREATE TABLE IF NOT EXISTS notification (
        event_id uuid PRIMARY KEY,
        customer_id varchar(80) NOT NULL,
        type varchar(40) NOT NULL,
        order_id uuid NOT NULL,
        summary varchar(200) NOT NULL,
        created_at timestamptz NOT NULL
      )
    `);
    await this.pool.query(`
      CREATE INDEX IF NOT EXISTS notification_customer_idx
      ON notification (customer_id, created_at)
    `);
  }

  async save(notice: Notice): Promise<boolean> {
    const result = await this.pool.query(
      `INSERT INTO notification (event_id, customer_id, type, order_id, summary, created_at)
       VALUES ($1, $2, $3, $4, $5, $6)
       ON CONFLICT (event_id) DO NOTHING`,
      [notice.eventId, notice.customerId, notice.type, notice.orderId, notice.summary, notice.createdAt],
    );
    return result.rowCount === 1;
  }

  async list(customerId: string): Promise<StoredNotice[]> {
    const result = await this.pool.query<{
      event_id: string;
      type: Notice["type"];
      order_id: string;
      summary: string;
      created_at: Date;
    }>(
      `SELECT event_id, type, order_id, summary, created_at
       FROM notification
       WHERE customer_id = $1
       ORDER BY created_at ASC
       LIMIT 50`,
      [customerId],
    );
    return result.rows.map((row) => ({
      eventId: row.event_id,
      type: row.type,
      orderId: row.order_id,
      summary: row.summary,
      createdAt: row.created_at.toISOString(),
    }));
  }

  async ready(): Promise<boolean> {
    try {
      await this.pool.query("SELECT 1");
      return true;
    } catch {
      return false;
    }
  }
}

function publicNotice(notice: Notice): StoredNotice {
  return {
    eventId: notice.eventId,
    type: notice.type,
    orderId: notice.orderId,
    summary: notice.summary,
    createdAt: notice.createdAt,
  };
}

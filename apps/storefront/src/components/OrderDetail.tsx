"use client";

import { useEffect, useState } from "react";
import { io, type Socket } from "socket.io-client";
import { money } from "@/lib/money";
import { mergeNotices, type LiveNotice } from "@/lib/notifications";
import styles from "@/app/shop.module.css";

type Item = {
  productId: string;
  name: string;
  quantity: number;
  unitPrice: number | string;
  lineTotal: number | string;
};

type Order = {
  id: string;
  status: string;
  currency: string;
  total: number | string;
  items: Item[];
  history: { status: string; createdAt: string }[];
};

export function OrderDetail({ id }: { id: string }) {
  const [order, setOrder] = useState<Order | null>(null);
  const [notices, setNotices] = useState<LiveNotice[]>([]);
  const [live, setLive] = useState<"connecting" | "live" | "reconnecting">("connecting");
  const [state, setState] = useState<"loading" | "ready" | "missing" | "signed-out" | "error">("loading");
  const [error, setError] = useState("");

  useEffect(() => {
    const controller = new AbortController();
    let socket: Socket | null = null;
    let closed = false;

    async function loadOrder() {
      const response = await fetch(`/api/orders/${id}`, { cache: "no-store", signal: controller.signal });
      if (response.status === 401) {
        setState("signed-out");
        return;
      }
      if (response.status === 404) {
        setState("missing");
        return;
      }
      if (!response.ok) {
        setState("error");
        return;
      }
      setOrder((await response.json()) as Order);
      setState("ready");
    }

    async function loadNotices() {
      const response = await fetch("/api/notifications", { cache: "no-store" });
      if (!response.ok) {
        return;
      }
      const body = (await response.json()) as { items?: LiveNotice[] };
      const mine = (body.items ?? []).filter((item) => item.orderId === id);
      setNotices((current) => mergeNotices(current, mine));
    }

    void loadOrder().catch((caught: unknown) => {
      if (caught instanceof DOMException && caught.name === "AbortError") {
        return;
      }
      setState("error");
    });

    void (async () => {
      const config = await fetch("/api/realtime", { cache: "no-store" }).then((response) => response.json() as Promise<{ url?: string }>);
      if (closed || !config.url) {
        return;
      }
      socket = io(config.url, { withCredentials: true, transports: ["websocket"] });
      socket.on("connect", () => {
        setLive("live");
        void loadOrder().catch(() => undefined);
        void loadNotices();
      });
      socket.on("disconnect", () => setLive("reconnecting"));
      socket.on("notification", (notice: LiveNotice) => {
        if (notice.orderId !== id) {
          return;
        }
        setNotices((current) => mergeNotices(current, [notice]));
        void loadOrder().catch(() => undefined);
      });
    })().catch(() => setLive("reconnecting"));

    return () => {
      closed = true;
      controller.abort();
      socket?.close();
    };
  }, [id]);

  async function cancel() {
    setError("");
    const response = await fetch(`/api/orders/${id}/cancel`, { method: "POST" });
    const body = (await response.json()) as Order & { error?: string };
    if (!response.ok) {
      setError(body.error ?? "The order could not be cancelled.");
      return;
    }
    setOrder(body);
  }

  if (state === "loading") {
    return <section className={styles.page}><p className={styles.muted}>Loading the order…</p></section>;
  }
  if (state === "signed-out") {
    return (
      <section className={styles.page}>
        <a className={styles.button} href={`/api/auth/login?return=/orders/${id}`}>Sign in</a>
      </section>
    );
  }
  if (state === "missing") {
    return <section className={styles.page}><p className={styles.empty}>That order was not found.</p></section>;
  }
  if (state === "error" || !order) {
    return <section className={styles.page}><p className={styles.error}>The order could not be loaded.</p></section>;
  }

  const liveText = live === "live"
    ? "Live updates are on. The status still comes from the order service."
    : live === "connecting"
      ? "Connecting for live updates."
      : "Reconnecting. The saved order is unchanged until the order service answers.";

  return (
    <section className={styles.page}>
      <p className={styles.kicker}>Order</p>
      <h1 className={styles.title}>{money(order.total, order.currency)}</h1>
      <p><span className={styles.badge}>{order.status}</span></p>
      <p className={styles.muted}>{liveText}</p>
      <div className={styles.panel}>
        {order.items.map((item) => (
          <div key={item.productId} className={styles.line}>
            <span>{item.name} × {item.quantity}</span>
            <span>{money(item.lineTotal, order.currency)}</span>
          </div>
        ))}
        <p className={styles.muted}>Unit prices were saved from the catalog when the order was created.</p>
        <ol className={styles.history}>
          {order.history.map((entry) => (
            <li key={`${entry.status}-${entry.createdAt}`}>{entry.status}</li>
          ))}
        </ol>
        {notices.length > 0 ? (
          <ul className={styles.history}>
            {notices.map((notice) => (
              <li key={notice.eventId}>{notice.summary}</li>
            ))}
          </ul>
        ) : null}
        {order.status === "PENDING_INVENTORY" || order.status === "PENDING_PAYMENT" || order.status === "COMPENSATING" ? (
          <p className={styles.muted}>This checkout is still running. The status updates when the order service answers.</p>
        ) : null}
        {order.status === "PENDING_INVENTORY" || order.status === "PENDING_PAYMENT" ? (
          <button className={styles.buttonQuiet} type="button" onClick={() => void cancel()}>Cancel order</button>
        ) : null}
        {error ? <p className={styles.error}>{error}</p> : null}
      </div>
    </section>
  );
}

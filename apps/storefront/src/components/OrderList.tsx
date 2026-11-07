"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { money } from "@/lib/money";
import styles from "@/app/shop.module.css";

type Order = {
  id: string;
  status: string;
  total: number | string;
  currency: string;
};

export function OrderList() {
  const [state, setState] = useState<"loading" | "signed-out" | "error" | "ready">("loading");
  const [orders, setOrders] = useState<Order[]>([]);
  const [detail, setDetail] = useState("");

  useEffect(() => {
    const controller = new AbortController();
    void fetch("/api/orders", { cache: "no-store", signal: controller.signal })
      .then(async (response) => {
        if (response.status === 401) {
          setState("signed-out");
          return;
        }
        const body = (await response.json()) as Order[] | { error?: string };
        if (!response.ok || !Array.isArray(body)) {
          setDetail(!Array.isArray(body) ? body.error ?? "Orders could not be loaded." : "Orders could not be loaded.");
          setState("error");
          return;
        }
        setOrders(body);
        setState("ready");
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === "AbortError") {
          return;
        }
        setState("error");
        setDetail("Orders could not be loaded.");
      });
    return () => controller.abort();
  }, []);

  if (state === "loading") {
    return <section className={styles.page}><p className={styles.muted}>Loading orders…</p></section>;
  }
  if (state === "signed-out") {
    return (
      <section className={styles.page}>
        <h1 className={styles.title}>Sign in to see orders.</h1>
        <a className={styles.button} href="/api/auth/login?return=/orders">Sign in</a>
      </section>
    );
  }
  if (state === "error") {
    return <section className={styles.page}><p className={styles.error}>{detail}</p></section>;
  }
  if (orders.length === 0) {
    return (
      <section className={styles.page}>
        <h1 className={styles.title}>No orders yet.</h1>
        <Link className={styles.button} href="/">Browse the shop</Link>
      </section>
    );
  }

  return (
    <section className={styles.page}>
      <h1 className={styles.title}>Your orders</h1>
      <div className={styles.grid}>
        {orders.map((order) => (
          <Link key={order.id} className={styles.card} href={`/orders/${order.id}`}>
            <span className={styles.badge}>{order.status}</span>
            <h2>{money(order.total, order.currency)}</h2>
            <p className={styles.muted}>{order.id}</p>
          </Link>
        ))}
      </div>
    </section>
  );
}

"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import styles from "./admin.module.css";

type Count = { totalItems: number };
type Campaign = { status: string };

export default function DashboardPage() {
  const [counts, setCounts] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      api<Count>("/api/backend/identity/users?size=1"),
      api<Count>("/api/backend/sellers?status=ACTIVE&size=1"),
      api<Count>("/api/backend/sellers?status=PENDING_APPROVAL&size=1"),
      api<Count>("/api/backend/products?size=1"),
      api<Count>("/api/backend/orders/monitor?size=1"),
      api<Count>("/api/backend/payments/monitor?size=1"),
      api<Campaign[]>("/api/backend/inventory/campaigns/monitor"),
    ])
      .then(([users, sellers, pending, products, orders, payments, campaigns]) => {
        if (cancelled) {
          return;
        }
        const active = campaigns.filter((campaign) => campaign.status === "ACTIVE").length;
        setCounts({
          Users: String(users.totalItems),
          "Active sellers": String(sellers.totalItems),
          "Pending sellers": String(pending.totalItems),
          Products: String(products.totalItems),
          Orders: String(orders.totalItems),
          Payments: String(payments.totalItems),
          "Campaigns shown": `${campaigns.length} (latest 50)`,
          "Active campaigns shown": String(active),
        });
      })
      .catch((caught: unknown) => {
        if (!cancelled) {
          setError(caught instanceof ApiError ? caught.message : "The desk could not load.");
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <main>
      <p className={styles.kicker}>Staff</p>
      <h1 className={styles.title}>Operational desk</h1>
      <p className={styles.lede}>
        Counts come from the staff list endpoints. Campaigns are the latest 50, not a full history.
        Signing in here replaces the localhost session used by the shop and Seller Center.
      </p>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      <section className={styles.cards}>
        {Object.entries(counts).map(([label, value]) => (
          <article key={label} className={styles.card}>
            <span>{label}</span>
            <strong>{value}</strong>
          </article>
        ))}
      </section>
      {!error && Object.keys(counts).length === 0 ? <p>Loading the desk.</p> : null}
      <p><Link href="/sellers?status=PENDING_APPROVAL">Review pending sellers</Link></p>
    </main>
  );
}

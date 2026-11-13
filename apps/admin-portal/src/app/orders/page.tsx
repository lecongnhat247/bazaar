"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import styles from "../admin.module.css";

type Order = { id: string; customerId: string; status: string; total: number; currency: string; createdAt: string };
type Page = { items: Order[]; totalItems: number };

export default function OrdersPage() {
  const [query, setQuery] = useState("");
  const [page, setPage] = useState<Page | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const handle = setTimeout(() => {
      const q = query.trim();
      api<Page>(`/api/backend/orders/monitor?size=20${q ? `&q=${encodeURIComponent(q)}` : ""}`)
        .then((body) => {
          setPage(body);
          setError(null);
        })
        .catch((caught: unknown) => setError(caught instanceof ApiError ? caught.message : "Orders could not load."));
    }, 250);
    return () => clearTimeout(handle);
  }, [query]);

  return (
    <main>
      <p className={styles.kicker}>Commerce</p>
      <h1 className={styles.title}>Orders</h1>
      <p className={styles.lede}>Search by order id, customer, or status. This view does not cancel an order.</p>
      <form className={styles.form} onSubmit={(event) => event.preventDefault()}>
        <label>Search
          <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Id, customer, or status" />
        </label>
      </form>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      <p className={styles.note}>{page ? `${page.totalItems} orders` : "Loading orders."}</p>
      <div className={styles.tableWrap}>
        <table>
          <thead><tr><th>Order</th><th>Customer</th><th>Status</th><th>Total</th></tr></thead>
          <tbody>
            {page?.items.map((order) => (
              <tr key={order.id}>
                <td><Link href={`/orders/${order.id}`}>{order.id}</Link></td>
                <td>{order.customerId}</td>
                <td><span className={styles.badge}>{order.status}</span></td>
                <td>{order.total} {order.currency}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </main>
  );
}

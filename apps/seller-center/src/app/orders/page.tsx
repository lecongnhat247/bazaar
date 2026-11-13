"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { money } from "@/lib/money";
import type { SalesPage } from "@/lib/types";
import styles from "../seller.module.css";

const STATUSES = [
  "PENDING_INVENTORY",
  "PENDING_PAYMENT",
  "PENDING_INVENTORY_COMMIT",
  "COMPENSATING",
  "CONFIRMED",
  "PAYMENT_FAILED",
  "CANCELLED",
  "FAILED",
];

export default function OrdersPage() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState("");
  const [query, setQuery] = useState("");
  const [result, setResult] = useState<SalesPage | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const params = new URLSearchParams({ page: String(page), size: "20" });
    if (status) {
      params.set("status", status);
    }
    if (query.trim()) {
      params.set("q", query.trim());
    }
    const handle = window.setTimeout(() => {
      api<SalesPage>(`/api/backend/orders/sales?${params.toString()}`)
        .then((body) => {
          setResult(body);
          setError(null);
        })
        .catch((caught: unknown) => setError(caught instanceof Error ? caught.message : "Sales could not be loaded."));
    }, 200);
    return () => window.clearTimeout(handle);
  }, [page, status, query]);

  return (
    <main>
      <p className={styles.kicker}>Orders</p>
      <h1 className={styles.title}>Your items</h1>
      <p className={styles.note}>
        Each row is a customer order that contains your products. Other sellers&apos; lines are not included. The status is the checkout saga for the whole order. There is no shipment record in this milestone.
      </p>
      <div className={styles.row}>
        <label>
          Status
          <select value={status} onChange={(event) => { setPage(0); setStatus(event.target.value); }}>
            <option value="">All</option>
            {STATUSES.map((value) => <option key={value}>{value}</option>)}
          </select>
        </label>
        <label>
          Search
          <input value={query} onChange={(event) => { setPage(0); setQuery(event.target.value); }} placeholder="Order id, name, or SKU" />
        </label>
      </div>
      {result ? <p>Matching orders: {result.totalOrders}. Your line total: {money(result.sellerTotal, result.currency)}. Still in progress: {result.pendingOrders}.</p> : null}
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      {!result ? <p>Loading sales.</p> : result.orders.length === 0 ? <p>No matching sales.</p> : (
        <div className={styles.tableWrap}>
          <table>
            <thead>
              <tr><th>Order</th><th>Buyer</th><th>Status</th><th>Your items</th><th>Your total</th></tr>
            </thead>
            <tbody>
              {result.orders.map((order) => (
                <tr key={order.id}>
                  <td><Link href={`/orders/${order.id}`}>{order.id.slice(0, 8)}</Link></td>
                  <td>{order.customerId}</td>
                  <td>{order.status}</td>
                  <td>{order.items.map((item) => `${item.name} × ${item.quantity}`).join(", ")}</td>
                  <td>{money(order.sellerTotal, order.currency)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {result && result.totalPages > 1 ? (
        <div className={styles.row}>
          <button className={styles.quiet} type="button" disabled={page === 0} onClick={() => setPage((current) => current - 1)}>Previous</button>
          <span>Page {page + 1} of {result.totalPages}</span>
          <button className={styles.quiet} type="button" disabled={page + 1 >= result.totalPages} onClick={() => setPage((current) => current + 1)}>Next</button>
        </div>
      ) : null}
    </main>
  );
}

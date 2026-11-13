"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import styles from "../admin.module.css";

type Payment = { id: string; orderId: string; payerId: string; amount: number; currency: string; status: string };
type Page = { items: Payment[]; totalItems: number };

export default function PaymentsPage() {
  const [query, setQuery] = useState("");
  const [page, setPage] = useState<Page | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const handle = setTimeout(() => {
      const q = query.trim();
      api<Page>(`/api/backend/payments/monitor?size=20${q ? `&q=${encodeURIComponent(q)}` : ""}`)
        .then((body) => {
          setPage(body);
          setError(null);
        })
        .catch((caught: unknown) => setError(caught instanceof ApiError ? caught.message : "Payments could not load."));
    }, 250);
    return () => clearTimeout(handle);
  }, [query]);

  return (
    <main>
      <p className={styles.kicker}>Commerce</p>
      <h1 className={styles.title}>Payments</h1>
      <p className={styles.lede}>Read-only. Simulate success, failure, and cancel are not available from this portal.</p>
      <form className={styles.form} onSubmit={(event) => event.preventDefault()}>
        <label>Search
          <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Id, order, payer, or status" />
        </label>
      </form>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      <p className={styles.note}>{page ? `${page.totalItems} payments` : "Loading payments."}</p>
      <div className={styles.tableWrap}>
        <table>
          <thead><tr><th>Payment</th><th>Order</th><th>Payer</th><th>Status</th><th>Amount</th></tr></thead>
          <tbody>
            {page?.items.map((payment) => (
              <tr key={payment.id}>
                <td><Link href={`/payments/${payment.id}`}>{payment.id}</Link></td>
                <td><Link href={`/orders/${payment.orderId}`}>{payment.orderId}</Link></td>
                <td>{payment.payerId}</td>
                <td><span className={styles.badge}>{payment.status}</span></td>
                <td>{payment.amount} {payment.currency}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </main>
  );
}

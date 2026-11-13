"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import styles from "../../admin.module.css";

type Item = { name: string; sellerId: string; quantity: number; lineTotal: number };
type History = { status: string; createdAt: string };
type Order = {
  id: string;
  customerId: string;
  status: string;
  currency: string;
  total: number;
  paymentId: string | null;
  items: Item[];
  history: History[];
};
type Page = { items: Order[] };

export default function OrderDetailPage() {
  const params = useParams<{ id: string }>();
  const [order, setOrder] = useState<Order | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<Page>(`/api/backend/orders/monitor?q=${encodeURIComponent(params.id)}&size=20`)
      .then((body) => {
        const match = body.items.find((item) => item.id === params.id);
        if (!match) {
          setError("This order is not in the current monitor page.");
          return;
        }
        setOrder(match);
      })
      .catch((caught: unknown) => setError(caught instanceof ApiError ? caught.message : "This order could not load."));
  }, [params.id]);

  return (
    <main>
      <p className={styles.kicker}>Order</p>
      <h1 className={styles.title}>{order?.id || "Order"}</h1>
      <p className={styles.note}>Read from the staff monitor. Cancelling stays on the buyer path.</p>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      {order ? (
        <>
          <p>{order.status} · customer {order.customerId} · {order.total} {order.currency}</p>
          {order.paymentId ? <p><Link href={`/payments/${order.paymentId}`}>Payment {order.paymentId}</Link></p> : <p>No payment id yet.</p>}
          <h2>Lines</h2>
          <div className={styles.tableWrap}>
            <table>
              <thead><tr><th>Product</th><th>Seller</th><th>Qty</th><th>Line</th></tr></thead>
              <tbody>
                {order.items.map((item) => (
                  <tr key={`${item.sellerId}-${item.name}`}>
                    <td>{item.name}</td>
                    <td>{item.sellerId}</td>
                    <td>{item.quantity}</td>
                    <td>{item.lineTotal}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <h2>Lifecycle</h2>
          <ul>
            {order.history.map((step) => <li key={`${step.status}-${step.createdAt}`}>{step.status} · {step.createdAt}</li>)}
          </ul>
        </>
      ) : null}
    </main>
  );
}

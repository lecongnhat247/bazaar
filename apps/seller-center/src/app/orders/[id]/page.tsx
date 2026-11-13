"use client";

import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { money } from "@/lib/money";
import type { SalesPage, SellerSale } from "@/lib/types";
import styles from "../../seller.module.css";

export default function OrderDetailPage() {
  const params = useParams<{ id: string }>();
  const [order, setOrder] = useState<SellerSale | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api<SalesPage>(`/api/backend/orders/sales?q=${encodeURIComponent(params.id)}&size=20`)
      .then((page) => {
        if (!cancelled) {
          setOrder(page.orders.find((item) => item.id === params.id) ?? null);
        }
      })
      .catch((caught: unknown) => {
        if (!cancelled) {
          setError(caught instanceof Error ? caught.message : "The order could not be loaded.");
        }
      });
    return () => {
      cancelled = true;
    };
  }, [params.id]);

  if (error) {
    return <p className={styles.error} role="alert">{error}</p>;
  }
  if (order === undefined) {
    return <p>Loading the order.</p>;
  }
  if (order === null) {
    return <p>This order has none of your items.</p>;
  }

  return (
    <main>
      <p className={styles.kicker}>Order</p>
      <h1 className={styles.title}>{order.id}</h1>
      <p className={styles.lede}>Checkout status <strong className={styles.badge}>{order.status}</strong>. Buyer {order.customerId}.</p>
      <p className={styles.note}>Your total {money(order.sellerTotal, order.currency)}. Lines from other sellers are not shown. There is no shipment or per-line fulfillment status yet.</p>
      <div className={styles.tableWrap}>
        <table>
          <thead>
            <tr><th>Item</th><th>SKU</th><th>Qty</th><th>Unit</th><th>Line</th></tr>
          </thead>
          <tbody>
            {order.items.map((item) => (
              <tr key={item.productId}>
                <td>{item.name}</td>
                <td>{item.sku}</td>
                <td>{item.quantity}</td>
                <td>{money(item.unitPrice, order.currency)}</td>
                <td>{money(item.lineTotal, order.currency)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <h2>Status history</h2>
      {order.history.length === 0 ? <p>No status history.</p> : (
        <ol>
          {order.history.map((entry) => (
            <li key={`${entry.status}-${entry.createdAt}`}>{entry.status} · {entry.createdAt}</li>
          ))}
        </ol>
      )}
    </main>
  );
}

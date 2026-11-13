"use client";

import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { money } from "@/lib/money";
import type { Fulfillment, SalesPage, SellerSale } from "@/lib/types";
import styles from "../../seller.module.css";

export default function OrderDetailPage() {
  const params = useParams<{ id: string }>();
  const [order, setOrder] = useState<SellerSale | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [version, setVersion] = useState(0);
  const [reference, setReference] = useState("");
  const [note, setNote] = useState("");

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
  }, [params.id, version]);

  async function advance(status: "PACKED" | "SHIPPED" | "DELIVERED" | "CANCELLED") {
    setError(null);
    setNotice(null);
    if (status === "SHIPPED" && !/^[A-Za-z0-9-]{4,40}$/.test(reference)) {
      setError("Enter a shipment reference of 4 to 40 letters, digits or dashes.");
      return;
    }
    if (status === "CANCELLED") {
      if (note.trim().length === 0) {
        setError("Give a reason before cancelling your part of the order.");
        return;
      }
      if (!window.confirm("Cancel your part of this order? This reverses your earnings for it.")) {
        return;
      }
    }
    try {
      await api<Fulfillment>(`/api/backend/orders/${params.id}/fulfillment`, {
        method: "PATCH",
        body: JSON.stringify({
          status,
          shipmentReference: status === "SHIPPED" ? reference : undefined,
          note: status === "CANCELLED" ? note.trim() : undefined,
        }),
      });
      setNotice(`Fulfillment is now ${status}.`);
      setReference("");
      setNote("");
      setVersion((current) => current + 1);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "The fulfillment could not be changed.");
    }
  }

  if (error && order === undefined) {
    return <p className={styles.error} role="alert">{error}</p>;
  }
  if (order === undefined) {
    return <p>Loading the order.</p>;
  }
  if (order === null) {
    return <p>This order has none of your items.</p>;
  }

  const fulfillment = order.fulfillment;
  const confirmed = order.status === "CONFIRMED";

  return (
    <main>
      <p className={styles.kicker}>Order</p>
      <h1 className={styles.title}>{order.id}</h1>
      <p className={styles.lede}>Checkout status <strong className={styles.badge}>{order.status}</strong>. Buyer {order.customerId}.</p>
      <p className={styles.note}>
        Your total {money(order.sellerTotal, order.currency)}
        {Number(order.sellerDiscount) > 0 ? `, of which a discount of ${money(order.sellerDiscount, order.currency)} applies to your lines` : ""}.
        Lines from other sellers are not shown. Orders with several sellers are fulfilled one seller at a time.
      </p>
      <div className={styles.tableWrap}>
        <table>
          <thead>
            <tr><th>Item</th><th>SKU</th><th>Qty</th><th>Unit</th><th>Line</th><th>Discount</th></tr>
          </thead>
          <tbody>
            {order.items.map((item) => (
              <tr key={item.productId}>
                <td>{item.name}</td>
                <td>{item.sku}</td>
                <td>{item.quantity}</td>
                <td>{money(item.unitPrice, order.currency)}</td>
                <td>{money(item.lineTotal, order.currency)}</td>
                <td>{money(item.discount, order.currency)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h2>Your fulfillment</h2>
      {!confirmed ? (
        <p className={styles.note}>Fulfillment opens when the order is CONFIRMED (stock committed and payment received).</p>
      ) : !fulfillment ? (
        <p className={styles.note}>The fulfillment group for this order is created the first time you act on it.</p>
      ) : null}
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      {notice ? <p className={styles.good} role="status">{notice}</p> : null}
      {fulfillment ? (
        <p>
          Status <strong className={styles.badge}>{fulfillment.status}</strong>
          {fulfillment.shipmentReference ? <> · shipment {fulfillment.shipmentReference}</> : null}
        </p>
      ) : null}
      {confirmed && (!fulfillment || ["PENDING", "PACKED", "SHIPPED"].includes(fulfillment.status)) ? (
        <div className={styles.form}>
          {(!fulfillment || fulfillment.status === "PENDING") ? (
            <button className={styles.button} type="button" onClick={() => advance("PACKED")}>Mark packed</button>
          ) : null}
          {fulfillment?.status === "PACKED" ? (
            <>
              <label>
                Shipment reference
                <input value={reference} onChange={(event) => setReference(event.target.value)} maxLength={40} />
              </label>
              <button className={styles.button} type="button" onClick={() => advance("SHIPPED")}>Mark shipped</button>
            </>
          ) : null}
          {fulfillment?.status === "SHIPPED" ? (
            <button className={styles.button} type="button" onClick={() => advance("DELIVERED")}>Mark delivered</button>
          ) : null}
          {(!fulfillment || fulfillment.status === "PENDING" || fulfillment.status === "PACKED") ? (
            <>
              <label>
                Cancellation reason
                <input value={note} onChange={(event) => setNote(event.target.value)} maxLength={200} />
              </label>
              <button className={styles.quiet} type="button" onClick={() => advance("CANCELLED")}>Cancel my part</button>
            </>
          ) : null}
        </div>
      ) : null}
      {fulfillment && fulfillment.history.length > 0 ? (
        <>
          <h3>Fulfillment history</h3>
          <ol>
            {fulfillment.history.map((entry) => (
              <li key={`${entry.to}-${entry.at}`}>
                {entry.from ? `${entry.from} → ` : ""}{entry.to} · {entry.at}{entry.note ? ` · ${entry.note}` : ""}
              </li>
            ))}
          </ol>
        </>
      ) : null}

      <h2>Checkout status history</h2>
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

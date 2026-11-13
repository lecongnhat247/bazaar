"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { ApiError, api } from "@/lib/api";
import { money } from "@/lib/money";
import type { ProductPage, SalesPage, Stock, StoreProfile } from "@/lib/types";
import styles from "./seller.module.css";

const LOW = 5;

export default function DashboardPage() {
  const [store, setStore] = useState<StoreProfile | null | undefined>(undefined);
  const [active, setActive] = useState<number | null>(null);
  const [sales, setSales] = useState<SalesPage | null>(null);
  const [low, setLow] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        const [storeResult, activePage, salesPage, products] = await Promise.all([
          api<StoreProfile>("/api/backend/sellers/me").catch((caught: unknown) => {
            if (caught instanceof ApiError && caught.status === 404) {
              return null;
            }
            throw caught;
          }),
          api<ProductPage>("/api/backend/products?mine=true&status=ACTIVE&size=1"),
          api<SalesPage>("/api/backend/orders/sales?page=0&size=5"),
          api<ProductPage>("/api/backend/products?mine=true&size=50"),
        ]);
        const stocks = await Promise.all(products.items.map(async (product) => {
          try {
            return await api<Stock>(`/api/backend/inventory/products/${product.id}`);
          } catch (caught) {
            if (caught instanceof ApiError && caught.status === 404) {
              return { productId: product.id, available: 0, reserved: 0, missing: true };
            }
            throw caught;
          }
        }));
        if (cancelled) {
          return;
        }
        setStore(storeResult);
        setActive(activePage.totalItems);
        setSales(salesPage);
        setLow(stocks.filter((stock) => "missing" in stock || stock.available <= LOW).length);
      } catch (caught) {
        if (!cancelled) {
          setError(caught instanceof Error ? caught.message : "The dashboard could not be loaded.");
        }
      }
    }
    void load();
    return () => {
      cancelled = true;
    };
  }, []);

  if (error) {
    return <p className={styles.error} role="alert">{error}</p>;
  }
  if (store === undefined || !sales || active == null || low == null) {
    return <p>Loading the desk.</p>;
  }

  return (
    <main>
      <p className={styles.kicker}>Dashboard</p>
      <h1 className={styles.title}>{store ? store.storeName : "Open your store"}</h1>
      {store ? <p className={styles.lede}>Store status: <strong className={styles.badge}>{store.status}</strong></p> : (
        <p className={styles.lede}>There is no store for this account yet. <Link href="/store">Create the store profile</Link>. An administrator must approve it before products can be published.</p>
      )}
      <section className={styles.cards} aria-label="Summary">
        <article className={styles.card}><span>Orders with your items</span><strong>{sales.totalOrders}</strong></article>
        <article className={styles.card}><span>Your line total</span><strong>{money(sales.sellerTotal, sales.currency)}</strong></article>
        <article className={styles.card}><span>Active products</span><strong>{active}</strong></article>
        <article className={styles.card}><span>Low or missing stock</span><strong>{low}</strong></article>
        <article className={styles.card}><span>Orders still in progress</span><strong>{sales.pendingOrders}</strong></article>
      </section>
      <p className={styles.note}>
        Low stock means available quantity of {LOW} or less, or no stock row, among the first 50 of your products. Orders still in progress use the checkout status of the whole customer order. Fulfillment is manual: mark your part of an order packed, shipped and delivered from its detail page. There is no real carrier tracking.
      </p>
      <h2>Recent sales</h2>
      {sales.orders.length === 0 ? <p>No sales yet.</p> : (
        <div className={styles.tableWrap}>
          <table>
            <thead>
              <tr><th>Order</th><th>Status</th><th>Your items</th><th>Your total</th></tr>
            </thead>
            <tbody>
              {sales.orders.map((order) => (
                <tr key={order.id}>
                  <td><Link href={`/orders/${order.id}`}>{order.id.slice(0, 8)}</Link></td>
                  <td>{order.status}</td>
                  <td>{order.items.map((item) => item.name).join(", ")}</td>
                  <td>{money(order.sellerTotal, order.currency)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </main>
  );
}

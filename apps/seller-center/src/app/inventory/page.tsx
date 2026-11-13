"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { ApiError, api } from "@/lib/api";
import type { Product, ProductPage, Stock } from "@/lib/types";
import styles from "../seller.module.css";

type Row = {
  product: Product;
  stock: Stock | null;
};

export default function InventoryPage() {
  const [rows, setRows] = useState<Row[] | null>(null);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        const page = await api<ProductPage>("/api/backend/products?mine=true&size=50");
        const loaded = await Promise.all(page.items.map(async (product) => {
          try {
            const stock = await api<Stock>(`/api/backend/inventory/products/${product.id}`);
            return { product, stock };
          } catch (caught) {
            if (caught instanceof ApiError && caught.status === 404) {
              return { product, stock: null };
            }
            throw caught;
          }
        }));
        if (!cancelled) {
          setRows(loaded);
          setDrafts(Object.fromEntries(loaded.map((row) => [
            row.product.id,
            String((row.stock?.available ?? 0) + (row.stock?.reserved ?? 0)),
          ])));
        }
      } catch (caught) {
        if (!cancelled) {
          setError(caught instanceof Error ? caught.message : "Stock could not be loaded.");
        }
      }
    }
    void load();
    return () => {
      cancelled = true;
    };
  }, []);

  async function save(row: Row) {
    const onHand = Number(drafts[row.product.id]);
    if (!Number.isInteger(onHand) || onHand < 0 || onHand > 1_000_000) {
      setError("On-hand stock must be a whole number from 0 to 1000000.");
      return;
    }
    if (onHand === 0 && !window.confirm(`Set on-hand stock for ${row.product.name} to 0?`)) {
      return;
    }
    setError(null);
    try {
      const stock = await api<Stock>(`/api/backend/inventory/products/${row.product.id}`, {
        method: "PUT",
        body: JSON.stringify({ onHand }),
      });
      setRows((current) => current?.map((item) => item.product.id === row.product.id ? { ...item, stock } : item) ?? null);
      setNotice(`Stock saved for ${row.product.name}.`);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "Stock could not be saved.");
    }
  }

  return (
    <main>
      <p className={styles.kicker}>Inventory</p>
      <h1 className={styles.title}>Stock</h1>
      <p className={styles.note}>
        Available and reserved quantities come from inventory. On-hand is available plus reserved. The server rejects a value below the reserved quantity. Movement history is stored, and this service does not list those rows yet.
      </p>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      {notice ? <p className={styles.good} role="status">{notice}</p> : null}
      {!rows ? <p>Loading stock.</p> : rows.length === 0 ? (
        <p>No products yet. <Link href="/products/new">Create one</Link> before setting stock.</p>
      ) : (
        <div className={styles.tableWrap}>
          <table>
            <thead>
              <tr><th>Product</th><th>Available</th><th>Reserved</th><th>On hand</th><th></th></tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.product.id}>
                  <td><Link href={`/products/${row.product.id}`}>{row.product.name}</Link></td>
                  <td>{row.stock ? row.stock.available : "No stock row"}</td>
                  <td>{row.stock ? row.stock.reserved : "—"}</td>
                  <td>
                    <input
                      aria-label={`On hand for ${row.product.name}`}
                      value={drafts[row.product.id] ?? ""}
                      onChange={(event) => setDrafts((current) => ({ ...current, [row.product.id]: event.target.value }))}
                      inputMode="numeric"
                    />
                  </td>
                  <td><button className={styles.button} type="button" onClick={() => save(row)}>Save</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </main>
  );
}

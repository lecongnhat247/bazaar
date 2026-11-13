"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { money } from "@/lib/money";
import type { ProductPage } from "@/lib/types";
import styles from "../seller.module.css";

export default function ProductsPage() {
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<ProductPage | null>(null);
  const [status, setStatus] = useState("");
  const [query, setQuery] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const params = new URLSearchParams({ mine: "true", page: String(page), size: "20" });
    if (status) {
      params.set("status", status);
    }
    if (query.trim()) {
      params.set("q", query.trim());
    }
    const handle = window.setTimeout(() => {
      api<ProductPage>(`/api/backend/products?${params.toString()}`)
        .then(setResult)
        .catch((caught: unknown) => setError(caught instanceof Error ? caught.message : "Products could not be loaded."));
    }, 200);
    return () => window.clearTimeout(handle);
  }, [page, status, query]);

  return (
    <main>
      <p className={styles.kicker}>Products</p>
      <h1 className={styles.title}>Your catalog</h1>
      <div className={styles.row}>
        <Link className={styles.button} href="/products/new">Create product</Link>
        <label>
          Status
          <select value={status} onChange={(event) => { setPage(0); setStatus(event.target.value); }}>
            <option value="">All</option>
            <option>DRAFT</option>
            <option>ACTIVE</option>
            <option>INACTIVE</option>
            <option>ARCHIVED</option>
          </select>
        </label>
        <label>
          Search
          <input value={query} onChange={(event) => { setPage(0); setQuery(event.target.value); }} />
        </label>
      </div>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      {!result ? <p>Loading products.</p> : result.items.length === 0 ? <p>No products match.</p> : (
        <div className={styles.tableWrap}>
          <table>
            <thead>
              <tr><th>Name</th><th>SKU</th><th>Price</th><th>Status</th></tr>
            </thead>
            <tbody>
              {result.items.map((product) => (
                <tr key={product.id}>
                  <td><Link href={`/products/${product.id}`}>{product.name}</Link></td>
                  <td>{product.sku}</td>
                  <td>{money(product.price, product.currency)}</td>
                  <td>{product.status}</td>
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

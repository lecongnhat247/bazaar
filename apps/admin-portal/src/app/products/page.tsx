"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import styles from "../admin.module.css";

type Product = { id: string; name: string; sku: string; sellerId: string; status: string; price: number; currency: string };
type Page = { items: Product[]; totalItems: number };

export default function ProductsPage() {
  const [page, setPage] = useState<Page | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<Page>("/api/backend/products?size=20")
      .then(setPage)
      .catch((caught: unknown) => setError(caught instanceof ApiError ? caught.message : "Products could not load."));
  }, []);

  return (
    <main>
      <p className={styles.kicker}>Catalog</p>
      <h1 className={styles.title}>Products</h1>
      <p className={styles.lede}>Staff can see draft and inactive products. A status change by an administrator needs a reason and is kept with the product.</p>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      <p className={styles.note}>{page ? `${page.totalItems} products` : "Loading products."}</p>
      <div className={styles.tableWrap}>
        <table>
          <thead><tr><th>Name</th><th>SKU</th><th>Seller</th><th>Status</th><th>Price</th></tr></thead>
          <tbody>
            {page?.items.map((product) => (
              <tr key={product.id}>
                <td><Link href={`/products/${product.id}`}>{product.name}</Link></td>
                <td>{product.sku}</td>
                <td>{product.sellerId}</td>
                <td><span className={styles.badge}>{product.status}</span></td>
                <td>{product.price} {product.currency}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </main>
  );
}

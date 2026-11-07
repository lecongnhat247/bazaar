"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { money } from "@/lib/money";
import styles from "@/app/shop.module.css";

type Product = {
  id: string;
  name: string;
  price: number | string;
  currency: string;
  sku: string;
};

type Category = { id: string; name: string };

type View =
  | { kind: "loading" }
  | { kind: "error"; detail: string }
  | { kind: "ready"; products: Product[] };

export function ProductBrowser() {
  const [query, setQuery] = useState("");
  const [categoryId, setCategoryId] = useState("");
  const [categories, setCategories] = useState<Category[]>([]);

  useEffect(() => {
    const controller = new AbortController();
    void fetch("/api/categories", { cache: "no-store", signal: controller.signal })
      .then((response) => response.json() as Promise<{ items?: Category[] }>)
      .then((body) => setCategories(body.items ?? []))
      .catch(() => undefined);
    return () => controller.abort();
  }, []);

  return (
    <section className={styles.page}>
      <div>
        <p className={styles.kicker}>Shop</p>
        <h1 className={styles.title}>Find something to take home.</h1>
        <p className={styles.lede}>
          Prices shown here come from the catalog. Checkout checks them again before a payment is simulated.
        </p>
      </div>
      <div className={styles.toolbar}>
        <input
          className={styles.input}
          value={query}
          placeholder="Search products"
          aria-label="Search products"
          onChange={(event) => setQuery(event.target.value)}
        />
      </div>
      <div className={styles.chips}>
        <button className={styles.chip} type="button" aria-pressed={categoryId === ""} onClick={() => setCategoryId("")}>
          All
        </button>
        {categories.map((category) => (
          <button
            key={category.id}
            className={styles.chip}
            type="button"
            aria-pressed={categoryId === category.id}
            onClick={() => setCategoryId(category.id)}
          >
            {category.name}
          </button>
        ))}
      </div>
      <ProductResults key={`${query}:${categoryId}`} query={query} categoryId={categoryId} />
    </section>
  );
}

function ProductResults({ query, categoryId }: { query: string; categoryId: string }) {
  const [view, setView] = useState<View>({ kind: "loading" });

  useEffect(() => {
    const controller = new AbortController();
    const params = new URLSearchParams({ size: "20" });
    if (query.trim()) {
      params.set("q", query.trim());
    }
    if (categoryId) {
      params.set("categoryId", categoryId);
    }
    void fetch(`/api/products?${params.toString()}`, { cache: "no-store", signal: controller.signal })
      .then(async (response) => {
        const body = (await response.json()) as { items?: Product[]; error?: string };
        if (!response.ok) {
          setView({ kind: "error", detail: body.error ?? "Products could not be loaded." });
          return;
        }
        setView({ kind: "ready", products: body.items ?? [] });
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === "AbortError") {
          return;
        }
        setView({ kind: "error", detail: "Products could not be loaded." });
      });
    return () => controller.abort();
  }, [query, categoryId]);

  return (
    <div aria-busy={view.kind === "loading"}>
      {view.kind === "loading" ? <p className={styles.muted}>Loading products…</p> : null}
      {view.kind === "error" ? <p className={styles.error}>{view.detail}</p> : null}
      {view.kind === "ready" && view.products.length === 0 ? (
        <p className={styles.empty}>No products match this search.</p>
      ) : null}
      {view.kind === "ready" && view.products.length > 0 ? (
        <div className={styles.grid}>
          {view.products.map((product) => (
            <Link key={product.id} className={styles.card} href={`/products/${product.id}`}>
              <div className={styles.mark} aria-hidden="true">{product.name.slice(0, 1)}</div>
              <h2>{product.name}</h2>
              <p className={styles.muted}>{product.sku}</p>
              <p className={styles.price}>{money(product.price, product.currency)}</p>
            </Link>
          ))}
        </div>
      ) : null}
    </div>
  );
}

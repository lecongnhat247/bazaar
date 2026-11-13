"use client";

import { useState } from "react";
import { api } from "@/lib/api";
import type { CategoryPage, Product } from "@/lib/types";
import styles from "../app/seller.module.css";

type Fields = {
  categoryId: string;
  name: string;
  description: string;
  sku: string;
  price: string;
  currency: string;
  imageUrl: string;
  altText: string;
};

export function ProductForm({
  categories,
  initial,
  onSaved,
}: {
  categories: CategoryPage["items"];
  initial?: Product;
  onSaved: (product: Product) => void;
}) {
  const [fields, setFields] = useState<Fields>({
    categoryId: initial?.categoryId ?? categories[0]?.id ?? "",
    name: initial?.name ?? "",
    description: initial?.description ?? "",
    sku: initial?.sku ?? "",
    price: initial ? Number(initial.price).toFixed(2) : "",
    currency: initial?.currency ?? "USD",
    imageUrl: initial?.images[0]?.url ?? "",
    altText: initial?.images[0]?.altText ?? "",
  });
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  function update(key: keyof Fields, value: string) {
    setFields((current) => ({ ...current, [key]: value }));
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    if (!fields.name.trim() || !fields.sku.trim() || !fields.categoryId) {
      setError("Name, SKU, and category are required.");
      return;
    }
    if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(fields.sku.trim())) {
      setError("SKU must start with a letter or number and use only letters, numbers, dots, underscores, or hyphens.");
      return;
    }
    if (!/^\d+(\.\d{1,2})?$/.test(fields.price) || Number(fields.price) < 0.01) {
      setError("Price must be at least 0.01 with at most two decimal places.");
      return;
    }
    if (fields.imageUrl.trim() && !/^https?:\/\//.test(fields.imageUrl.trim())) {
      setError("An image address must start with http:// or https://.");
      return;
    }
    const body = {
      categoryId: fields.categoryId,
      name: fields.name.trim(),
      description: fields.description.trim(),
      sku: fields.sku.trim(),
      price: fields.price,
      currency: fields.currency.trim().toUpperCase(),
      images: fields.imageUrl.trim()
        ? [{ url: fields.imageUrl.trim(), altText: fields.altText.trim() }]
        : [],
      attributes: {},
    };
    setPending(true);
    try {
      const saved = initial
        ? await api<Product>(`/api/backend/products/${initial.id}`, { method: "PUT", body: JSON.stringify(body) })
        : await api<Product>("/api/backend/products", { method: "POST", body: JSON.stringify(body) });
      onSaved(saved);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "The product could not be saved.");
    } finally {
      setPending(false);
    }
  }

  return (
    <form className={styles.form} onSubmit={submit}>
      <label>
        Category
        <select value={fields.categoryId} onChange={(event) => update("categoryId", event.target.value)} required>
          {categories.map((category) => (
            <option key={category.id} value={category.id}>{category.name}</option>
          ))}
        </select>
      </label>
      <label>
        Name
        <input value={fields.name} onChange={(event) => update("name", event.target.value)} maxLength={160} required />
      </label>
      <label>
        Description
        <textarea value={fields.description} onChange={(event) => update("description", event.target.value)} maxLength={4000} rows={4} />
      </label>
      <label>
        SKU
        <input value={fields.sku} onChange={(event) => update("sku", event.target.value)} maxLength={64} required />
      </label>
      <label>
        Price
        <input value={fields.price} onChange={(event) => update("price", event.target.value)} inputMode="decimal" required />
      </label>
      <label>
        Currency
        <input value={fields.currency} onChange={(event) => update("currency", event.target.value)} maxLength={3} required />
      </label>
      <label>
        Image address
        <input value={fields.imageUrl} onChange={(event) => update("imageUrl", event.target.value)} placeholder="https://" />
      </label>
      <label>
        Image description
        <input value={fields.altText} onChange={(event) => update("altText", event.target.value)} maxLength={160} />
      </label>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      <button className={styles.button} type="submit" disabled={pending}>{pending ? "Saving…" : "Save product"}</button>
    </form>
  );
}

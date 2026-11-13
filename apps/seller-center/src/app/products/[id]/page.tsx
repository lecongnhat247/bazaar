"use client";

import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { ProductForm } from "@/components/ProductForm";
import { api } from "@/lib/api";
import type { CategoryPage, Product } from "@/lib/types";
import styles from "../../seller.module.css";

const NEXT: Record<Product["status"], Product["status"][]> = {
  DRAFT: ["ACTIVE", "INACTIVE", "ARCHIVED"],
  ACTIVE: ["INACTIVE", "ARCHIVED"],
  INACTIVE: ["ACTIVE", "ARCHIVED"],
  ARCHIVED: [],
};

export default function ProductDetailPage() {
  const params = useParams<{ id: string }>();
  const [product, setProduct] = useState<Product | null>(null);
  const [categories, setCategories] = useState<CategoryPage["items"]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      api<Product>(`/api/backend/products/${params.id}`),
      api<CategoryPage>("/api/backend/categories?size=50"),
    ]).then(([item, page]) => {
      if (!cancelled) {
        setProduct(item);
        setCategories(page.items);
      }
    }).catch((caught: unknown) => {
      if (!cancelled) {
        setError(caught instanceof Error ? caught.message : "The product could not be loaded.");
      }
    });
    return () => {
      cancelled = true;
    };
  }, [params.id]);

  async function changeStatus(status: Product["status"]) {
    if (!product) {
      return;
    }
    if (status === "ARCHIVED" && !window.confirm("Archive this product? An archived product cannot be published again.")) {
      return;
    }
    setError(null);
    try {
      const saved = await api<Product>(`/api/backend/products/${product.id}/status`, {
        method: "PATCH",
        body: JSON.stringify({ status }),
      });
      setProduct(saved);
      setNotice(`Status is ${saved.status}.`);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "The status could not be changed.");
    }
  }

  if (error && !product) {
    return <p className={styles.error} role="alert">{error}</p>;
  }
  if (!product) {
    return <p>Loading the product.</p>;
  }

  return (
    <main>
      <p className={styles.kicker}>Product</p>
      <h1 className={styles.title}>{product.name}</h1>
      <p className={styles.lede}>Status <strong className={styles.badge}>{product.status}</strong></p>
      <div className={styles.row}>
        {NEXT[product.status].map((status) => (
          <button key={status} className={status === "ARCHIVED" ? styles.quiet : styles.button} type="button" onClick={() => changeStatus(status)}>
            {status === "ACTIVE" ? "Publish" : status === "INACTIVE" ? "Unpublish" : status === "ARCHIVED" ? "Archive" : status}
          </button>
        ))}
      </div>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      {notice ? <p className={styles.good} role="status">{notice}</p> : null}
      {product.images[0] ? <p className={styles.note}>Image: {product.images[0].url}</p> : <p className={styles.note}>No image address yet.</p>}
      <ProductForm categories={categories} initial={product} onSaved={setProduct} />
    </main>
  );
}

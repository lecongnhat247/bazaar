"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { ProductForm } from "@/components/ProductForm";
import { api } from "@/lib/api";
import type { CategoryPage } from "@/lib/types";
import styles from "../../seller.module.css";

export default function NewProductPage() {
  const router = useRouter();
  const [categories, setCategories] = useState<CategoryPage["items"] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<CategoryPage>("/api/backend/categories?size=50")
      .then((page) => setCategories(page.items))
      .catch((caught: unknown) => setError(caught instanceof Error ? caught.message : "Categories could not be loaded."));
  }, []);

  return (
    <main>
      <p className={styles.kicker}>Products</p>
      <h1 className={styles.title}>New product</h1>
      <p className={styles.lede}>A new product starts as a draft. Publishing requires an active store. The seller id is taken from your token.</p>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      {!categories ? <p>Loading categories.</p> : categories.length === 0 ? <p>No categories are available.</p> : (
        <ProductForm categories={categories} onSaved={(product) => router.push(`/products/${product.id}`)} />
      )}
    </main>
  );
}

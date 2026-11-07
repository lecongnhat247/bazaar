"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useCart } from "@/components/CartProvider";
import { money, priceText } from "@/lib/money";
import styles from "@/app/shop.module.css";

type Product = {
  id: string;
  name: string;
  description?: string | null;
  sku: string;
  price: number | string;
  currency: string;
};

export function ProductDetail({ id }: { id: string }) {
  const cart = useCart();
  const [product, setProduct] = useState<Product | null>(null);
  const [quantity, setQuantity] = useState(1);
  const [state, setState] = useState<"loading" | "ready" | "missing" | "error">("loading");
  const [notice, setNotice] = useState("");

  useEffect(() => {
    const controller = new AbortController();
    void fetch(`/api/products/${id}`, { cache: "no-store", signal: controller.signal })
      .then(async (response) => {
        if (response.status === 404) {
          setState("missing");
          return;
        }
        if (!response.ok) {
          setState("error");
          return;
        }
        setProduct((await response.json()) as Product);
        setState("ready");
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === "AbortError") {
          return;
        }
        setState("error");
      });
    return () => controller.abort();
  }, [id]);

  if (state === "loading") {
    return <section className={styles.page}><p className={styles.muted}>Loading product…</p></section>;
  }
  if (state === "missing") {
    return <section className={styles.page}><p className={styles.empty}>This product is not available.</p></section>;
  }
  if (state === "error" || !product) {
    return <section className={styles.page}><p className={styles.error}>The product could not be loaded.</p></section>;
  }

  return (
    <section className={styles.page}>
      <p className={styles.kicker}>{product.sku}</p>
      <h1 className={styles.title}>{product.name}</h1>
      <p className={styles.price}>{money(product.price, product.currency)}</p>
      <p className={styles.lede}>{product.description || "No description was published."}</p>
      <div className={styles.actions}>
        <label>
          Quantity
          <input
            className={styles.quantity}
            type="number"
            min={1}
            max={100}
            value={quantity}
            onChange={(event) => setQuantity(Number(event.target.value))}
          />
        </label>
        <button
          className={styles.button}
          type="button"
          onClick={() => {
            cart.add({
              productId: product.id,
              name: product.name,
              unitPrice: priceText(product.price),
              currency: product.currency,
              quantity: Number.isInteger(quantity) ? quantity : 1,
            });
            setNotice("Added to the cart on this browser.");
          }}
        >
          Add to cart
        </button>
        <Link className={styles.buttonQuiet} href="/cart">Go to cart</Link>
      </div>
      {notice ? <p className={styles.good}>{notice}</p> : null}
    </section>
  );
}

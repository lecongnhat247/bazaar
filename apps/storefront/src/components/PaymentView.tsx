"use client";

import Link from "next/link";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { useCart } from "@/components/CartProvider";
import styles from "@/app/shop.module.css";

export function PaymentView() {
  const cart = useCart();
  const router = useRouter();
  const [error, setError] = useState("");
  const [pending, setPending] = useState<"SUCCESS" | "FAILURE" | null>(null);

  async function pay(outcome: "SUCCESS" | "FAILURE") {
    setError("");
    setPending(outcome);
    const fingerprint = cart.lines.map((line) => `${line.productId}:${line.quantity}`).join(",");
    const stored = window.sessionStorage.getItem("bazaar-checkout");
    const parsed = stored ? JSON.parse(stored) as { key?: string; fingerprint?: string } : {};
    const key = parsed.fingerprint === fingerprint && parsed.key ? parsed.key : crypto.randomUUID();
    window.sessionStorage.setItem("bazaar-checkout", JSON.stringify({ key, fingerprint, outcome }));
    try {
      const response = await fetch("/api/checkout", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Idempotency-Key": key,
        },
        body: JSON.stringify({
          outcome,
          items: cart.lines.map((line) => ({ productId: line.productId, quantity: line.quantity })),
        }),
      });
      const body = (await response.json()) as { id?: string; error?: string };
      if (response.status === 401) {
        router.push("/api/auth/login?return=/checkout/payment");
        return;
      }
      if (!response.ok || !body.id) {
        setError(body.error ?? "Checkout did not complete.");
        setPending(null);
        return;
      }
      cart.clear();
      window.sessionStorage.removeItem("bazaar-checkout");
      router.push(`/orders/${body.id}`);
    } catch {
      setError("Checkout did not complete. Retry keeps the same attempt.");
      setPending(null);
    }
  }

  if (cart.lines.length === 0) {
    return (
      <section className={styles.page}>
        <h1 className={styles.title}>The cart is empty.</h1>
        <Link className={styles.button} href="/">Browse the shop</Link>
      </section>
    );
  }

  return (
    <section className={styles.page}>
      <p className={styles.kicker}>Payment simulation</p>
      <h1 className={styles.title}>No card is charged.</h1>
      <p className={styles.lede}>
        Success confirms the order and keeps the stock sold. Failure releases the hold and marks the order failed.
      </p>
      <div className={styles.actions}>
        <button className={styles.button} type="button" disabled={pending !== null} onClick={() => void pay("SUCCESS")}>
          {pending === "SUCCESS" ? "Paying…" : "Simulate success"}
        </button>
        <button className={styles.buttonQuiet} type="button" disabled={pending !== null} onClick={() => void pay("FAILURE")}>
          {pending === "FAILURE" ? "Paying…" : "Simulate failure"}
        </button>
      </div>
      {error ? <p className={styles.error}>{error}</p> : null}
    </section>
  );
}

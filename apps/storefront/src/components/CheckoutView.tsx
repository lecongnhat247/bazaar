"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useCart } from "@/components/CartProvider";
import { cartTotal } from "@/lib/cart";
import { money } from "@/lib/money";
import styles from "@/app/shop.module.css";

export function CheckoutView() {
  const cart = useCart();
  const [authenticated, setAuthenticated] = useState<boolean | null>(null);
  const total = cartTotal(cart.lines);

  useEffect(() => {
    void fetch("/api/session", { cache: "no-store" })
      .then((response) => response.json() as Promise<{ authenticated: boolean }>)
      .then((body) => setAuthenticated(body.authenticated))
      .catch(() => setAuthenticated(false));
  }, []);

  if (cart.lines.length === 0) {
    return (
      <section className={styles.page}>
        <h1 className={styles.title}>Nothing to check out.</h1>
        <Link className={styles.button} href="/cart">Back to cart</Link>
      </section>
    );
  }

  return (
    <section className={styles.page}>
      <p className={styles.kicker}>Checkout</p>
      <h1 className={styles.title}>Review the cart.</h1>
      <div className={styles.panel}>
        {cart.lines.map((line) => (
          <div key={line.productId} className={styles.line}>
            <span>{line.name} × {line.quantity}</span>
            <span>{money(line.unitPrice, line.currency)}</span>
          </div>
        ))}
        {total ? <p className={styles.muted}>Browser estimate {money(total.amount, total.currency)}. The order total replaces this.</p> : null}
        {authenticated === null ? <p className={styles.muted}>Checking your session…</p> : null}
        {authenticated === false ? (
          <a className={styles.button} href="/api/auth/login?return=/checkout">Sign in to continue</a>
        ) : null}
        {authenticated ? <Link className={styles.button} href="/checkout/payment">Choose a payment result</Link> : null}
      </div>
    </section>
  );
}

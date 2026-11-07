"use client";

import Link from "next/link";
import { useCart } from "@/components/CartProvider";
import { cartTotal } from "@/lib/cart";
import { money } from "@/lib/money";
import styles from "@/app/shop.module.css";

export function CartView() {
  const cart = useCart();
  const total = cartTotal(cart.lines);

  if (cart.lines.length === 0) {
    return (
      <section className={styles.page}>
        <h1 className={styles.title}>Your cart is empty.</h1>
        <Link className={styles.button} href="/">Browse the shop</Link>
      </section>
    );
  }

  return (
    <section className={styles.page}>
      <h1 className={styles.title}>Cart</h1>
      <p className={styles.lede}>This cart stays in the browser. The server will price the order itself.</p>
      <div className={styles.panel}>
        {cart.lines.map((line) => (
          <div key={line.productId} className={styles.line}>
            <div>
              <strong>{line.name}</strong>
              <p className={styles.muted}>{money(line.unitPrice, line.currency)} each</p>
            </div>
            <div className={styles.actions}>
              <input
                className={styles.quantity}
                type="number"
                min={1}
                max={100}
                aria-label={`Quantity for ${line.name}`}
                value={line.quantity}
                onChange={(event) => cart.setQuantity(line.productId, Number(event.target.value))}
              />
              <button className={styles.buttonQuiet} type="button" onClick={() => cart.remove(line.productId)}>
                Remove
              </button>
            </div>
          </div>
        ))}
        {total ? <p className={styles.price}>Shown total {money(total.amount, total.currency)}</p> : null}
        <Link className={styles.button} href="/checkout">Continue to checkout</Link>
      </div>
    </section>
  );
}

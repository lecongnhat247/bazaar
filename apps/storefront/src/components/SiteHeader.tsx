"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { cartCount } from "@/lib/cart";
import { useCart } from "@/components/CartProvider";
import styles from "./SiteHeader.module.css";

type Session = { authenticated: boolean; name?: string };

export function SiteHeader() {
  const cart = useCart();
  const [session, setSession] = useState<Session>({ authenticated: false });

  useEffect(() => {
    const controller = new AbortController();
    void fetch("/api/session", { cache: "no-store", signal: controller.signal })
      .then((response) => response.json() as Promise<Session>)
      .then(setSession)
      .catch(() => undefined);
    return () => controller.abort();
  }, []);

  const count = cartCount(cart.lines);

  return (
    <header className={styles.header}>
      <Link className={styles.brand} href="/">
        BAZAAR
      </Link>
      <nav className={styles.nav} aria-label="Shop">
        <Link href="/">Shop</Link>
        <Link href="/cart">Cart{count > 0 ? ` (${count})` : ""}</Link>
        <Link href="/orders">Orders</Link>
        {session.authenticated ? (
          <a href="/api/auth/logout">Sign out{session.name ? ` ${session.name}` : ""}</a>
        ) : (
          <Link href="/login">Sign in</Link>
        )}
      </nav>
    </header>
  );
}

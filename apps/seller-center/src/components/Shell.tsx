"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { isSeller } from "@/lib/roles";
import styles from "../app/seller.module.css";

type Session = {
  authenticated: boolean;
  name?: string;
  roles: string[];
};

const LINKS = [
  ["/", "Dashboard"],
  ["/store", "Store"],
  ["/products", "Products"],
  ["/inventory", "Inventory"],
  ["/orders", "Orders"],
  ["/earnings", "Earnings"],
  ["/promotions", "Promotions"],
];

export function Shell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const [session, setSession] = useState<Session | null>(null);
  const login = pathname.startsWith("/login");

  useEffect(() => {
    if (login) {
      return;
    }
    let cancelled = false;
    fetch("/api/session")
      .then((response) => response.json())
      .then((body: Session) => {
        if (!cancelled) {
          setSession(body);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setSession({ authenticated: false, roles: [] });
        }
      });
    return () => {
      cancelled = true;
    };
  }, [login]);

  return (
    <div className={styles.frame}>
      <aside className={styles.nav}>
        <p className={styles.brand}><Link href="/">Seller Center</Link></p>
        <p className={styles.who}>{session?.authenticated ? session.name : "BAZAAR"}</p>
        {session?.authenticated && isSeller(session.roles) ? (
          <nav className={styles.links} aria-label="Seller">
            {LINKS.map(([href, label]) => (
              <Link key={href} href={href} aria-current={pathname === href ? "page" : undefined}>{label}</Link>
            ))}
          </nav>
        ) : null}
        {session?.authenticated ? <a className={styles.signOut} href="/api/auth/logout">Sign out</a> : null}
      </aside>
      <div className={styles.main}>
        {login ? children : gate(session, children)}
      </div>
    </div>
  );
}

function gate(session: Session | null, children: React.ReactNode) {
  if (!session) {
    return <p>Checking your session.</p>;
  }
  if (!session.authenticated) {
    return (
      <>
        <h1 className={styles.title}>Sign in to the seller desk.</h1>
        <p className={styles.lede}>Seller Center uses the same Keycloak shop client. The token stays in an HTTP-only cookie.</p>
        <a className={styles.button} href="/api/auth/login?return=/">Continue to sign in</a>
      </>
    );
  }
  if (!isSeller(session.roles)) {
    return (
      <>
        <h1 className={styles.title}>This desk is for a seller.</h1>
        <p className={styles.lede}>The signed-in account does not have the SELLER role. A customer account cannot open a store here.</p>
      </>
    );
  }
  return children;
}

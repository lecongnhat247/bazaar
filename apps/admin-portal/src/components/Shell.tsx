"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { isAdmin, isStaff } from "@/lib/roles";
import styles from "../app/admin.module.css";

export type Session = {
  authenticated: boolean;
  name?: string;
  roles: string[];
};

const LINKS = [
  ["/", "Dashboard"],
  ["/users", "Users"],
  ["/sellers", "Sellers"],
  ["/products", "Products"],
  ["/categories", "Categories"],
  ["/campaigns", "Campaigns"],
  ["/orders", "Orders"],
  ["/payments", "Payments"],
  ["/audit", "Audit"],
];

export function Shell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const session = useSession(pathname.startsWith("/login"));

  return (
    <div className={styles.frame}>
      <aside className={styles.nav}>
        <p className={styles.brand}><Link href="/">Admin Portal</Link></p>
        <p className={styles.who}>{session?.authenticated ? session.name : "BAZAAR"}</p>
        {session?.authenticated && isStaff(session.roles) ? (
          <nav className={styles.links} aria-label="Staff">
            {LINKS.map(([href, label]) => (
              <Link key={href} href={href} aria-current={pathname === href ? "page" : undefined}>{label}</Link>
            ))}
          </nav>
        ) : null}
        {session?.authenticated ? <a className={styles.signOut} href="/api/auth/logout">Sign out</a> : null}
      </aside>
      <div className={styles.main}>
        {pathname.startsWith("/login") ? children : gate(session, children)}
      </div>
    </div>
  );
}

export function useSession(skip = false): Session | null {
  const [session, setSession] = useState<Session | null>(null);
  useEffect(() => {
    if (skip) {
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
  }, [skip]);
  return session;
}

function gate(session: Session | null, children: React.ReactNode) {
  if (!session) {
    return <p>Checking your session.</p>;
  }
  if (!session.authenticated) {
    return (
      <>
        <h1 className={styles.title}>Sign in to the staff desk.</h1>
        <p className={styles.lede}>Admin Portal uses the same Keycloak shop client. The token stays in an HTTP-only cookie shared by localhost.</p>
        <a className={styles.button} href="/api/auth/login?return=/">Continue to sign in</a>
      </>
    );
  }
  if (!isStaff(session.roles)) {
    return (
      <>
        <h1 className={styles.title}>This desk is for staff.</h1>
        <p className={styles.lede}>The signed-in account needs ADMIN or OPERATOR. A seller or customer cannot open this portal.</p>
      </>
    );
  }
  if (!isAdmin(session.roles)) {
    return (
      <>
        <p className={styles.note}>Signed in as an operator. You can read the desk. Status changes stay with an administrator.</p>
        {children}
      </>
    );
  }
  return children;
}

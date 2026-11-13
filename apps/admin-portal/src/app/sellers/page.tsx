"use client";

import Link from "next/link";
import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import styles from "../admin.module.css";

type Seller = { keycloakSubject: string; status: string; storeName: string | null };
type Page = { items: Seller[]; totalItems: number };

const STATUSES = ["", "PENDING_APPROVAL", "ACTIVE", "SUSPENDED", "REJECTED"];

export default function SellersPage() {
  return (
    <Suspense fallback={<p>Loading sellers.</p>}>
      <SellerList />
    </Suspense>
  );
}

function SellerList() {
  const initial = useSearchParams().get("status") ?? "";
  const [status, setStatus] = useState(STATUSES.includes(initial) ? initial : "");
  const [page, setPage] = useState<Page | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const query = status ? `?status=${status}&size=20` : "?size=20";
    api<Page>(`/api/backend/sellers${query}`)
      .then((body) => {
        setPage(body);
        setError(null);
      })
      .catch((caught: unknown) => setError(caught instanceof ApiError ? caught.message : "Sellers could not load."));
  }, [status]);

  return (
    <main>
      <p className={styles.kicker}>Stores</p>
      <h1 className={styles.title}>Sellers</h1>
      <form className={styles.form} onSubmit={(event) => event.preventDefault()}>
        <label>Status
          <select value={status} onChange={(event) => setStatus(event.target.value)}>
            <option value="">All</option>
            {STATUSES.filter(Boolean).map((value) => <option key={value} value={value}>{value}</option>)}
          </select>
        </label>
      </form>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      <p className={styles.note}>{page ? `${page.totalItems} sellers` : "Loading sellers."}</p>
      <div className={styles.tableWrap}>
        <table>
          <thead><tr><th>Store</th><th>Subject</th><th>Status</th></tr></thead>
          <tbody>
            {page?.items.map((seller) => (
              <tr key={seller.keycloakSubject}>
                <td><Link href={`/sellers/${encodeURIComponent(seller.keycloakSubject)}`}>{seller.storeName || "Unnamed store"}</Link></td>
                <td>{seller.keycloakSubject}</td>
                <td><span className={styles.badge}>{seller.status}</span></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </main>
  );
}

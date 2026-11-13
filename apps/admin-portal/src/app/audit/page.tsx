"use client";

import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import styles from "../admin.module.css";

type Entry = {
  area: string;
  actorSubject: string;
  action: string;
  targetSubject: string;
  reason: string | null;
  resultStatus: string;
  createdAt: string;
};
type Page = { items: Entry[]; totalItems: number };

export default function AuditPage() {
  const [page, setPage] = useState<Page | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<Page>("/api/backend/identity/audit?size=50")
      .then(setPage)
      .catch((caught: unknown) => setError(caught instanceof ApiError ? caught.message : "The audit log could not load."));
  }, []);

  return (
    <main>
      <p className={styles.kicker}>Record</p>
      <h1 className={styles.title}>Audit</h1>
      <p className={styles.lede}>Seller decisions and account-status changes from identity. Product moderation stays on the product page because catalog does not read the identity database.</p>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      <p className={styles.note}>{page ? `${page.totalItems} entries` : "Loading the audit log."}</p>
      <div className={styles.tableWrap}>
        <table>
          <thead><tr><th>When</th><th>Area</th><th>Action</th><th>Actor</th><th>Target</th><th>Result</th><th>Reason</th></tr></thead>
          <tbody>
            {page?.items.map((entry) => (
              <tr key={`${entry.area}-${entry.createdAt}-${entry.action}`}>
                <td>{entry.createdAt}</td>
                <td>{entry.area}</td>
                <td>{entry.action}</td>
                <td>{entry.actorSubject}</td>
                <td>{entry.targetSubject}</td>
                <td>{entry.resultStatus}</td>
                <td>{entry.reason || ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </main>
  );
}

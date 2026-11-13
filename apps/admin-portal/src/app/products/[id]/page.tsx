"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { isAdmin } from "@/lib/roles";
import { useSession } from "@/components/Shell";
import styles from "../../admin.module.css";

type Product = { id: string; name: string; sku: string; sellerId: string; status: string; description: string | null };
type Entry = { actorSubject: string; reason: string; resultStatus: string; createdAt: string };

const STATUSES = ["DRAFT", "ACTIVE", "INACTIVE", "ARCHIVED"];

export default function ProductDetailPage() {
  const params = useParams<{ id: string }>();
  const session = useSession();
  const [product, setProduct] = useState<Product | null>(null);
  const [history, setHistory] = useState<Entry[]>([]);
  const [status, setStatus] = useState("INACTIVE");
  const [reason, setReason] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const admin = session ? isAdmin(session.roles) : false;

  useEffect(() => {
    api<Product>(`/api/backend/products/${params.id}`)
      .then((body) => {
        setProduct(body);
        setStatus(body.status);
      })
      .catch((caught: unknown) => setError(caught instanceof ApiError ? caught.message : "This product could not load."));
    api<Entry[]>(`/api/backend/products/${params.id}/moderation`)
      .then(setHistory)
      .catch(() => setHistory([]));
  }, [params.id]);

  async function save() {
    setError(null);
    setMessage(null);
    try {
      const updated = await api<Product>(`/api/backend/products/${params.id}/status`, {
        method: "PATCH",
        body: JSON.stringify({ status, reason }),
      });
      setProduct(updated);
      setMessage(`Product is ${updated.status}.`);
      setHistory(await api<Entry[]>(`/api/backend/products/${params.id}/moderation`));
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "The status did not change.");
    }
  }

  return (
    <main>
      <p className={styles.kicker}>Moderation</p>
      <h1 className={styles.title}>{product?.name || "Product"}</h1>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      {message ? <p className={styles.good}>{message}</p> : null}
      {product ? <p>{product.sku} · seller {product.sellerId} · {product.status}</p> : null}
      {product ? <p>{product.description || "No description."}</p> : null}
      {admin ? (
        <form className={styles.form} onSubmit={(event) => { event.preventDefault(); void save(); }}>
          <label>Status
            <select value={status} onChange={(event) => setStatus(event.target.value)}>
              {STATUSES.map((value) => <option key={value} value={value}>{value}</option>)}
            </select>
          </label>
          <label>Reason
            <input value={reason} onChange={(event) => setReason(event.target.value)} maxLength={500} required />
          </label>
          <button className={styles.button} type="submit">Save status</button>
        </form>
      ) : <p className={styles.note}>An operator can read this product and its moderation history. Only an administrator can change the status.</p>}
      <h2>Moderation history</h2>
      <div className={styles.tableWrap}>
        <table>
          <thead><tr><th>When</th><th>Actor</th><th>Result</th><th>Reason</th></tr></thead>
          <tbody>
            {history.map((entry) => (
              <tr key={`${entry.createdAt}-${entry.resultStatus}`}>
                <td>{entry.createdAt}</td>
                <td>{entry.actorSubject}</td>
                <td>{entry.resultStatus}</td>
                <td>{entry.reason}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {history.length === 0 ? <p className={styles.note}>No administrator status change is recorded yet. A seller publishing their own product does not add a row.</p> : null}
    </main>
  );
}

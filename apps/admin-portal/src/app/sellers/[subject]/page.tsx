"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { isAdmin } from "@/lib/roles";
import { useSession } from "@/components/Shell";
import styles from "../../admin.module.css";

type Seller = {
  keycloakSubject: string;
  status: string;
  storeName: string | null;
  description: string | null;
  contactEmail: string | null;
  phone: string | null;
};

const ACTIONS = [
  ["approve", "Approve"],
  ["reject", "Reject"],
  ["suspend", "Suspend"],
  ["reactivate", "Reactivate"],
] as const;

export default function SellerDetailPage() {
  const params = useParams<{ subject: string }>();
  const subject = decodeURIComponent(params.subject);
  const session = useSession();
  const [seller, setSeller] = useState<Seller | null>(null);
  const [reason, setReason] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const admin = session ? isAdmin(session.roles) : false;

  useEffect(() => {
    api<Seller>(`/api/backend/sellers/${encodeURIComponent(subject)}`)
      .then(setSeller)
      .catch((caught: unknown) => setError(caught instanceof ApiError ? caught.message : "This seller could not load."));
  }, [subject]);

  async function decide(action: string) {
    setError(null);
    setMessage(null);
    try {
      const updated = await api<Seller>(`/api/backend/sellers/${encodeURIComponent(subject)}/${action}`, {
        method: "POST",
        body: JSON.stringify({ reason }),
      });
      setSeller(updated);
      setMessage(`Seller is ${updated.status}.`);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "The decision did not save.");
    }
  }

  return (
    <main>
      <p className={styles.kicker}>Store</p>
      <h1 className={styles.title}>{seller?.storeName || subject}</h1>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      {message ? <p className={styles.good}>{message}</p> : null}
      {seller ? (
        <>
          <p>{seller.status} · {seller.contactEmail || "No contact email"} · {seller.phone || "No phone"}</p>
          <p>{seller.description || "No description."}</p>
          {admin ? (
            <form className={styles.form} onSubmit={(event) => event.preventDefault()}>
              <label>Reason
                <input value={reason} onChange={(event) => setReason(event.target.value)} maxLength={500} required />
              </label>
              <div className={styles.row}>
                {ACTIONS.map(([action, label]) => (
                  <button key={action} className={action === "approve" || action === "reactivate" ? styles.button : styles.quiet} type="button" onClick={() => decide(action)}>{label}</button>
                ))}
              </div>
            </form>
          ) : <p className={styles.note}>An operator can read this seller. Only an administrator can approve, reject, suspend, or reactivate.</p>}
        </>
      ) : null}
    </main>
  );
}

"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import { isAdmin } from "@/lib/roles";
import { useSession } from "@/components/Shell";
import styles from "../../admin.module.css";

type User = {
  id: string;
  keycloakSubject: string;
  email: string | null;
  displayName: string | null;
  status: string;
  roles: string[];
};

export default function UserDetailPage() {
  const params = useParams<{ id: string }>();
  const session = useSession();
  const [user, setUser] = useState<User | null>(null);
  const [reason, setReason] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const admin = session ? isAdmin(session.roles) : false;

  useEffect(() => {
    api<User>(`/api/backend/identity/users/${params.id}`)
      .then(setUser)
      .catch((caught: unknown) => setError(caught instanceof ApiError ? caught.message : "This user could not load."));
  }, [params.id]);

  async function change(status: "ACTIVE" | "DISABLED") {
    setError(null);
    setMessage(null);
    try {
      const updated = await api<User>(`/api/backend/identity/users/${params.id}/status`, {
        method: "PATCH",
        body: JSON.stringify({ status, reason }),
      });
      setUser(updated);
      setMessage(`Account is ${updated.status}.`);
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "The status did not change.");
    }
  }

  return (
    <main>
      <p className={styles.kicker}>Account</p>
      <h1 className={styles.title}>{user?.displayName || user?.keycloakSubject || "User"}</h1>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      {message ? <p className={styles.good}>{message}</p> : null}
      {user ? (
        <>
          <p>{user.email || "No email"} · {user.roles.join(", ") || "No application role"} · {user.status}</p>
          {admin ? (
            <form className={styles.form} onSubmit={(event) => event.preventDefault()}>
              <label>Reason
                <input value={reason} onChange={(event) => setReason(event.target.value)} maxLength={500} required />
              </label>
              <div className={styles.row}>
                <button className={styles.button} type="button" onClick={() => change("DISABLED")}>Disable</button>
                <button className={styles.quiet} type="button" onClick={() => change("ACTIVE")}>Reactivate</button>
              </div>
            </form>
          ) : <p className={styles.note}>An operator can read this account. Only an administrator can change its status.</p>}
        </>
      ) : null}
    </main>
  );
}

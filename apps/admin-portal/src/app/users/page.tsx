"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import styles from "../admin.module.css";

type User = {
  id: string;
  keycloakSubject: string;
  email: string | null;
  displayName: string | null;
  status: string;
  roles: string[];
};

type Page = { items: User[]; totalItems: number };

export default function UsersPage() {
  const [query, setQuery] = useState("");
  const [page, setPage] = useState<Page | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const handle = setTimeout(() => {
      const q = query.trim();
      api<Page>(`/api/backend/identity/users?size=20${q ? `&q=${encodeURIComponent(q)}` : ""}`)
        .then((body) => {
          setPage(body);
          setError(null);
        })
        .catch((caught: unknown) => setError(caught instanceof ApiError ? caught.message : "Users could not load."));
    }, 250);
    return () => clearTimeout(handle);
  }, [query]);

  return (
    <main>
      <p className={styles.kicker}>Accounts</p>
      <h1 className={styles.title}>Users</h1>
      <p className={styles.lede}>
        This list is profiles created on a first visit to identity. It is not the full Keycloak directory.
        DISABLED is stored and audited here. Other services still accept a valid token.
      </p>
      <form className={styles.form} onSubmit={(event) => event.preventDefault()}>
        <label>Search
          <input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Name, email, or subject" />
        </label>
      </form>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      <p className={styles.note}>{page ? `${page.totalItems} profiles` : "Loading users."}</p>
      <div className={styles.tableWrap}>
        <table>
          <thead>
            <tr><th>Name</th><th>Subject</th><th>Roles</th><th>Status</th></tr>
          </thead>
          <tbody>
            {page?.items.map((user) => (
              <tr key={user.id}>
                <td><Link href={`/users/${user.id}`}>{user.displayName || user.email || user.keycloakSubject}</Link></td>
                <td>{user.keycloakSubject}</td>
                <td>{user.roles.join(", ")}</td>
                <td><span className={styles.badge}>{user.status}</span></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </main>
  );
}

"use client";

import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { isAdmin } from "@/lib/roles";
import { useSession } from "@/components/Shell";
import styles from "../admin.module.css";

type Category = { id: string; name: string; slug: string };
type Page = { items: Category[]; totalItems: number };

export default function CategoriesPage() {
  const session = useSession();
  const [page, setPage] = useState<Page | null>(null);
  const [name, setName] = useState("");
  const [editing, setEditing] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const admin = session ? isAdmin(session.roles) : false;

  function load() {
    api<Page>("/api/backend/categories?size=50")
      .then(setPage)
      .catch((caught: unknown) => setError(caught instanceof ApiError ? caught.message : "Categories could not load."));
  }

  useEffect(() => {
    load();
  }, []);

  async function save(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setMessage(null);
    try {
      if (editing) {
        await api(`/api/backend/categories/${editing}`, { method: "PATCH", body: JSON.stringify({ name }) });
        setMessage("Category updated.");
      } else {
        await api("/api/backend/categories", { method: "POST", body: JSON.stringify({ name }) });
        setMessage("Category created.");
      }
      setName("");
      setEditing(null);
      load();
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "The category did not save.");
    }
  }

  return (
    <main>
      <p className={styles.kicker}>Catalog</p>
      <h1 className={styles.title}>Categories</h1>
      <p className={styles.lede}>Categories stay a flat list of name and slug. There is no parent and no separate status.</p>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      {message ? <p className={styles.good}>{message}</p> : null}
      {admin ? (
        <form className={styles.form} onSubmit={(event) => void save(event)}>
          <label>{editing ? "New name" : "Name"}
            <input value={name} onChange={(event) => setName(event.target.value)} maxLength={80} required />
          </label>
          <div className={styles.row}>
            <button className={styles.button} type="submit">{editing ? "Update" : "Create"}</button>
            {editing ? <button className={styles.quiet} type="button" onClick={() => { setEditing(null); setName(""); }}>Cancel</button> : null}
          </div>
        </form>
      ) : <p className={styles.note}>An operator can read categories. Only an administrator can create or rename one.</p>}
      <div className={styles.tableWrap}>
        <table>
          <thead><tr><th>Name</th><th>Slug</th>{admin ? <th>Action</th> : null}</tr></thead>
          <tbody>
            {page?.items.map((category) => (
              <tr key={category.id}>
                <td>{category.name}</td>
                <td>{category.slug}</td>
                {admin ? <td><button className={styles.quiet} type="button" onClick={() => { setEditing(category.id); setName(category.name); }}>Rename</button></td> : null}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </main>
  );
}

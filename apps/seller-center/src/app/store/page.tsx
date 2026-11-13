"use client";

import { useEffect, useState } from "react";
import { ApiError, api } from "@/lib/api";
import type { StoreProfile } from "@/lib/types";
import styles from "../seller.module.css";

type Fields = {
  storeName: string;
  description: string;
  logoUrl: string;
  contactEmail: string;
  phone: string;
};

const EMPTY: Fields = { storeName: "", description: "", logoUrl: "", contactEmail: "", phone: "" };

export default function StorePage() {
  const [store, setStore] = useState<StoreProfile | null | undefined>(undefined);
  const [fields, setFields] = useState<Fields>(EMPTY);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api<StoreProfile>("/api/backend/sellers/me")
      .then((body) => {
        if (cancelled) {
          return;
        }
        setStore(body);
        setFields({
          storeName: body.storeName ?? "",
          description: body.description ?? "",
          logoUrl: body.logoUrl ?? "",
          contactEmail: body.contactEmail ?? "",
          phone: body.phone ?? "",
        });
      })
      .catch((caught: unknown) => {
        if (cancelled) {
          return;
        }
        if (caught instanceof ApiError && caught.status === 404) {
          setStore(null);
          return;
        }
        setError(caught instanceof Error ? caught.message : "The store could not be loaded.");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  function update(key: keyof Fields, value: string) {
    setFields((current) => ({ ...current, [key]: value }));
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setSaved(null);
    if (!fields.storeName.trim()) {
      setError("Store name is required.");
      return;
    }
    if (fields.logoUrl.trim() && !/^https?:\/\//.test(fields.logoUrl.trim())) {
      setError("The logo address must start with http:// or https://.");
      return;
    }
    if (fields.contactEmail.trim() && !/^[^@\s]+@[^@\s]+$/.test(fields.contactEmail.trim())) {
      setError("Contact email must contain one @.");
      return;
    }
    const body = {
      storeName: fields.storeName.trim(),
      description: fields.description.trim(),
      logoUrl: fields.logoUrl.trim(),
      contactEmail: fields.contactEmail.trim(),
      phone: fields.phone.trim(),
    };
    setPending(true);
    try {
      const next = store
        ? await api<StoreProfile>("/api/backend/sellers/me/store", { method: "PATCH", body: JSON.stringify(body) })
        : await api<StoreProfile>("/api/backend/sellers/me", { method: "POST", body: JSON.stringify(body) });
      setStore(next);
      setSaved(store ? "Store details saved." : "Store submitted for approval.");
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "The store could not be saved.");
    } finally {
      setPending(false);
    }
  }

  if (store === undefined && !error) {
    return <p>Loading the store.</p>;
  }
  const locked = store?.status === "SUSPENDED" || store?.status === "REJECTED";

  return (
    <main>
      <p className={styles.kicker}>Store</p>
      <h1 className={styles.title}>{store ? store.storeName : "Create your store"}</h1>
      {store ? <p className={styles.lede}>Status <strong className={styles.badge}>{store.status}</strong></p> : (
        <p className={styles.lede}>A new store stays pending until an administrator approves it.</p>
      )}
      <form className={styles.form} onSubmit={submit}>
        <label>Store name<input value={fields.storeName} onChange={(event) => update("storeName", event.target.value)} maxLength={120} required disabled={locked} /></label>
        <label>Description<textarea value={fields.description} onChange={(event) => update("description", event.target.value)} maxLength={4000} rows={4} disabled={locked} /></label>
        <label>Logo address<input value={fields.logoUrl} onChange={(event) => update("logoUrl", event.target.value)} placeholder="https://" disabled={locked} /></label>
        <label>Contact email<input value={fields.contactEmail} onChange={(event) => update("contactEmail", event.target.value)} type="email" disabled={locked} /></label>
        <label>Phone<input value={fields.phone} onChange={(event) => update("phone", event.target.value)} maxLength={40} disabled={locked} /></label>
        {error ? <p className={styles.error} role="alert">{error}</p> : null}
        {saved ? <p className={styles.good} role="status">{saved}</p> : null}
        {locked ? <p>This store cannot be edited while it is {store?.status}.</p> : (
          <button className={styles.button} type="submit" disabled={pending}>{pending ? "Saving…" : store ? "Save store" : "Submit store"}</button>
        )}
      </form>
    </main>
  );
}

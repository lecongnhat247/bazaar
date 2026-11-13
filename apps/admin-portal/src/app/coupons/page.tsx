"use client";

import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { isAdmin } from "@/lib/roles";
import { useSession } from "@/components/Shell";
import styles from "../admin.module.css";

type Coupon = {
  terms: {
    code: string;
    discountType: "PERCENT" | "FIXED";
    percent: number | null;
    fixedAmount: number | null;
    currency: string;
    minSubtotal: number;
    maxDiscount: number | null;
    sellerId: string | null;
    fundedBy: "PLATFORM" | "SELLER";
    startsAt: string;
    endsAt: string;
    usageLimit: number | null;
    perCustomerLimit: number;
    status: "ACTIVE" | "DISABLED";
  };
  used: number;
  createdBy: string;
};

export default function CouponsPage() {
  const session = useSession();
  const admin = session ? isAdmin(session.roles) : false;
  const [coupons, setCoupons] = useState<Coupon[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [form, setForm] = useState({
    code: "",
    discountType: "PERCENT",
    value: "10",
    minSubtotal: "0",
    maxDiscount: "",
    sellerId: "",
    fundedBy: "PLATFORM",
    startsAt: "",
    endsAt: "",
    usageLimit: "",
    perCustomerLimit: "1",
  });

  function load() {
    api<Coupon[]>("/api/backend/orders/coupons")
      .then(setCoupons)
      .catch((caught: unknown) => setError(caught instanceof ApiError ? caught.message : "Coupons could not load."));
  }

  useEffect(() => {
    load();
  }, []);

  function set(name: keyof typeof form, value: string) {
    setForm((current) => ({ ...current, [name]: value }));
  }

  async function create(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setMessage(null);
    if (!form.startsAt || !form.endsAt || new Date(form.endsAt) <= new Date(form.startsAt)) {
      setError("The coupon must end after it starts.");
      return;
    }
    const percent = form.discountType === "PERCENT";
    try {
      await api("/api/backend/orders/coupons", {
        method: "POST",
        body: JSON.stringify({
          code: form.code.trim(),
          discountType: form.discountType,
          percent: percent ? Number(form.value) : null,
          fixedAmount: percent ? null : Number(form.value),
          currency: "USD",
          minSubtotal: Number(form.minSubtotal || 0),
          maxDiscount: form.maxDiscount ? Number(form.maxDiscount) : null,
          sellerId: form.sellerId.trim() || null,
          fundedBy: form.sellerId.trim() ? form.fundedBy : "PLATFORM",
          startsAt: new Date(form.startsAt).toISOString(),
          endsAt: new Date(form.endsAt).toISOString(),
          usageLimit: form.usageLimit ? Number(form.usageLimit) : null,
          perCustomerLimit: Number(form.perCustomerLimit || 1),
        }),
      });
      setMessage("Coupon created.");
      load();
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "The coupon could not be created.");
    }
  }

  async function toggle(coupon: Coupon) {
    setError(null);
    setMessage(null);
    const status = coupon.terms.status === "ACTIVE" ? "DISABLED" : "ACTIVE";
    try {
      await api(`/api/backend/orders/coupons/${coupon.terms.code}`, { method: "PATCH", body: JSON.stringify({ status }) });
      setMessage(`${coupon.terms.code} is now ${status}.`);
      load();
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "The coupon did not change.");
    }
  }

  return (
    <main>
      <p className={styles.kicker}>Promotions</p>
      <h1 className={styles.title}>Coupons</h1>
      <p className={styles.lede}>
        The checkout only receives a coupon code. The order service decides eligibility and calculates the discount, so a browser can never set an amount.
        One coupon per order, and not together with a flash-sale price. Usage counts orders that are still live or confirmed; a cancelled or failed order gives its use back.
      </p>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      {message ? <p className={styles.good}>{message}</p> : null}
      {admin ? (
        <form className={styles.form} onSubmit={create}>
          <label>Code<input value={form.code} onChange={(event) => set("code", event.target.value)} maxLength={40} required /></label>
          <label>
            Type
            <select value={form.discountType} onChange={(event) => set("discountType", event.target.value)}>
              <option value="PERCENT">Percent (max 90)</option>
              <option value="FIXED">Fixed amount (USD)</option>
            </select>
          </label>
          <label>{form.discountType === "PERCENT" ? "Percent" : "Amount (USD)"}<input value={form.value} onChange={(event) => set("value", event.target.value)} inputMode="decimal" /></label>
          <label>Minimum subtotal<input value={form.minSubtotal} onChange={(event) => set("minSubtotal", event.target.value)} inputMode="decimal" /></label>
          <label>Maximum discount<input value={form.maxDiscount} onChange={(event) => set("maxDiscount", event.target.value)} inputMode="decimal" placeholder="none" /></label>
          <label>Seller subject (optional)<input value={form.sellerId} onChange={(event) => set("sellerId", event.target.value)} placeholder="all sellers" /></label>
          <label>
            Funded by
            <select value={form.fundedBy} onChange={(event) => set("fundedBy", event.target.value)} disabled={!form.sellerId.trim()}>
              <option value="PLATFORM">Platform</option>
              <option value="SELLER">Seller</option>
            </select>
          </label>
          <label>Starts<input type="datetime-local" value={form.startsAt} onChange={(event) => set("startsAt", event.target.value)} /></label>
          <label>Ends<input type="datetime-local" value={form.endsAt} onChange={(event) => set("endsAt", event.target.value)} /></label>
          <label>Total uses<input value={form.usageLimit} onChange={(event) => set("usageLimit", event.target.value)} inputMode="numeric" placeholder="unlimited" /></label>
          <label>Per customer<input value={form.perCustomerLimit} onChange={(event) => set("perCustomerLimit", event.target.value)} inputMode="numeric" /></label>
          <button className={styles.button} type="submit">Create coupon</button>
        </form>
      ) : <p className={styles.note}>Creating or disabling a coupon needs an administrator.</p>}
      <div className={styles.tableWrap}>
        <table>
          <thead>
            <tr><th>Code</th><th>Discount</th><th>Scope</th><th>Window</th><th>Used</th><th>Per customer</th><th>Status</th>{admin ? <th></th> : null}</tr>
          </thead>
          <tbody>
            {coupons.map((coupon) => (
              <tr key={coupon.terms.code}>
                <td>{coupon.terms.code}</td>
                <td>
                  {coupon.terms.discountType === "PERCENT" ? `${coupon.terms.percent}%` : `${coupon.terms.fixedAmount} ${coupon.terms.currency}`}
                  {coupon.terms.maxDiscount ? ` (max ${coupon.terms.maxDiscount})` : ""}
                  {coupon.terms.minSubtotal > 0 ? ` · min ${coupon.terms.minSubtotal}` : ""}
                </td>
                <td>{coupon.terms.sellerId ?? "All sellers"} · {coupon.terms.fundedBy}</td>
                <td>{coupon.terms.startsAt} → {coupon.terms.endsAt}</td>
                <td>{coupon.used}{coupon.terms.usageLimit ? ` / ${coupon.terms.usageLimit}` : ""}</td>
                <td>{coupon.terms.perCustomerLimit}</td>
                <td><span className={styles.badge}>{coupon.terms.status}</span></td>
                {admin ? (
                  <td><button className={styles.quiet} type="button" onClick={() => toggle(coupon)}>{coupon.terms.status === "ACTIVE" ? "Disable" : "Enable"}</button></td>
                ) : null}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </main>
  );
}

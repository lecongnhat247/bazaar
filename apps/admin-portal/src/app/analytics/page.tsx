"use client";

import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { money } from "@/lib/money";
import { isAdmin } from "@/lib/roles";
import { useSession } from "@/components/Shell";
import styles from "../admin.module.css";

type MoneyRow = { currency: string; amount: string };
type Overview = {
  asOf: string | null;
  eventuallyConsistent: boolean;
  totalOrders: number;
  revenue: MoneyRow[];
  ordersByStatus: { status: string; count: number }[];
  payments: { status: string; currency: string; count: number; amount: string }[];
  confirmedWithoutLines: number;
};
type Sales = { grain: string; buckets: { period: string; currency: string; orders: number; revenue: string }[] };
type Products = { items: { productId: string; name: string | null; units: number; currency: string | null; revenue: string | null; revenueComplete: boolean }[] };
type Flash = { note: string; campaigns: { campaignId: string; currency: string; orders: number; revenue: string }[] };
type Reconciliation = { confirmedWithoutLines: number; confirmedWithUnpricedLines: number; lineTotalMismatches: number; paymentMismatches: number };

export default function AnalyticsPage() {
  const session = useSession();
  const admin = session ? isAdmin(session.roles) : false;
  const [grain, setGrain] = useState("day");
  const [overview, setOverview] = useState<Overview | null>(null);
  const [sales, setSales] = useState<Sales | null>(null);
  const [products, setProducts] = useState<Products | null>(null);
  const [flash, setFlash] = useState<Flash | null>(null);
  const [reconciliation, setReconciliation] = useState<Reconciliation | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  function fail(caught: unknown, fallback: string) {
    setError(caught instanceof ApiError ? caught.message : fallback);
  }

  function load(nextGrain = grain) {
    setError(null);
    api<Overview>("/api/backend/analytics/platform/overview").then(setOverview).catch((caught: unknown) => fail(caught, "The overview could not load."));
    api<Sales>(`/api/backend/analytics/platform/sales?grain=${nextGrain}`).then(setSales).catch((caught: unknown) => fail(caught, "Sales could not load."));
    api<Products>("/api/backend/analytics/products/performance").then(setProducts).catch((caught: unknown) => fail(caught, "Products could not load."));
    api<Flash>("/api/backend/analytics/flash-sales/performance").then(setFlash).catch((caught: unknown) => fail(caught, "Flash sales could not load."));
    api<Reconciliation>("/api/backend/analytics/platform/reconciliation").then(setReconciliation).catch((caught: unknown) => fail(caught, "Reconciliation could not load."));
  }

  useEffect(() => {
    let cancelled = false;
    api<Overview>("/api/backend/analytics/platform/overview")
      .then((body) => { if (!cancelled) setOverview(body); })
      .catch((caught: unknown) => { if (!cancelled) fail(caught, "The overview could not load."); });
    api<Sales>("/api/backend/analytics/platform/sales?grain=day")
      .then((body) => { if (!cancelled) setSales(body); })
      .catch((caught: unknown) => { if (!cancelled) fail(caught, "Sales could not load."); });
    api<Products>("/api/backend/analytics/products/performance")
      .then((body) => { if (!cancelled) setProducts(body); })
      .catch((caught: unknown) => { if (!cancelled) fail(caught, "Products could not load."); });
    api<Flash>("/api/backend/analytics/flash-sales/performance")
      .then((body) => { if (!cancelled) setFlash(body); })
      .catch((caught: unknown) => { if (!cancelled) fail(caught, "Flash sales could not load."); });
    api<Reconciliation>("/api/backend/analytics/platform/reconciliation")
      .then((body) => { if (!cancelled) setReconciliation(body); })
      .catch((caught: unknown) => { if (!cancelled) fail(caught, "Reconciliation could not load."); });
    return () => { cancelled = true; };
  }, []);

  async function rebuild() {
    setMessage(null);
    setError(null);
    try {
      const body = await api<{ events: number }>("/api/backend/analytics/rebuild", { method: "POST" });
      setMessage(`Rebuilt ${body.events} stored events.`);
      load();
    } catch (caught) {
      fail(caught, "Rebuild was rejected.");
    }
  }

  return (
    <main>
      <p className={styles.kicker}>Reports</p>
      <h1 className={styles.title}>Analytics</h1>
      <p className={styles.lede}>
        Eventually consistent. {overview?.asOf ? `Last apply ${overview.asOf}.` : "No event has been applied yet."}
        {" "}Confirmed orders without line facts stay in platform revenue and are not treated as zero seller revenue.
        Settlement is not on the event stream.
      </p>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      {message ? <p className={styles.note}>{message}</p> : null}
      <p className={styles.note}>{overview ? `${overview.totalOrders} orders` : "Loading the overview."}</p>
      <div className={styles.cards}>
        {(overview?.revenue ?? []).map((row) => (
          <p key={row.currency}>{money(row.amount, row.currency)} confirmed</p>
        ))}
      </div>
      <div className={styles.tableWrap}>
        <table>
          <caption>Orders by status</caption>
          <thead><tr><th>Status</th><th>Count</th></tr></thead>
          <tbody>
            {(overview?.ordersByStatus ?? []).map((row) => (
              <tr key={row.status}><td>{row.status}</td><td>{row.count}</td></tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className={styles.tableWrap}>
        <table>
          <caption>Payments</caption>
          <thead><tr><th>Status</th><th>Count</th><th>Amount</th></tr></thead>
          <tbody>
            {(overview?.payments ?? []).map((row) => (
              <tr key={`${row.status}-${row.currency}`}><td>{row.status}</td><td>{row.count}</td><td>{money(row.amount, row.currency)}</td></tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className={styles.note}>
        Confirmed without lines: {overview?.confirmedWithoutLines ?? "—"}.
        Unpriced lines: {reconciliation?.confirmedWithUnpricedLines ?? "—"}.
        Line mismatches: {reconciliation?.lineTotalMismatches ?? "—"}.
        Payment mismatches: {reconciliation?.paymentMismatches ?? "—"}.
      </p>
      <form className={styles.form} onSubmit={(event) => event.preventDefault()}>
        <label>Grain
          <select value={grain} onChange={(event) => { setGrain(event.target.value); load(event.target.value); }}>
            <option value="day">Day</option>
            <option value="week">Week</option>
            <option value="month">Month</option>
          </select>
        </label>
      </form>
      <div className={styles.tableWrap}>
        <table>
          <caption>Sales</caption>
          <thead><tr><th>Period</th><th>Orders</th><th>Revenue</th></tr></thead>
          <tbody>
            {(sales?.buckets ?? []).map((row) => (
              <tr key={`${row.period}-${row.currency}`}><td>{row.period}</td><td>{row.orders}</td><td>{money(row.revenue, row.currency)}</td></tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className={styles.tableWrap}>
        <table>
          <caption>Products</caption>
          <thead><tr><th>Product</th><th>Units</th><th>Revenue</th></tr></thead>
          <tbody>
            {(products?.items ?? []).map((row) => (
              <tr key={row.productId}>
                <td>{row.name ?? row.productId}</td>
                <td>{row.units}</td>
                <td>{row.revenueComplete ? money(row.revenue, row.currency) : "incomplete"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className={styles.note}>{flash?.note ?? "Loading flash-sale figures."}</p>
      <div className={styles.tableWrap}>
        <table>
          <caption>Campaigns</caption>
          <thead><tr><th>Campaign</th><th>Orders</th><th>Revenue</th></tr></thead>
          <tbody>
            {(flash?.campaigns ?? []).map((row) => (
              <tr key={`${row.campaignId}-${row.currency}`}><td>{row.campaignId}</td><td>{row.orders}</td><td>{money(row.revenue, row.currency)}</td></tr>
            ))}
          </tbody>
        </table>
      </div>
      {admin ? <button type="button" onClick={rebuild}>Rebuild stored events</button> : <p className={styles.note}>Only an administrator can rebuild.</p>}
    </main>
  );
}

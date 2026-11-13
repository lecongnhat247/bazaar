"use client";

import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { isAdmin } from "@/lib/roles";
import { useSession } from "@/components/Shell";
import styles from "../admin.module.css";

type Entry = {
  id: string;
  orderId: string;
  sellerId: string;
  account: string;
  entryType: string;
  currency: string;
  amount: number;
  rateBps: number | null;
  reversesEntryId: string | null;
  reason: string | null;
  createdBy: string;
  createdAt: string;
};
type LedgerPage = { items: Entry[]; page: number; total: number; pages: number };
type Rates = { defaultRateBps: number; rates: { sellerId: string; rateBps: number; effectiveFrom: string; createdBy: string }[] };
type Settlement = { id: string; sellerId: string; currency: string; amount: number; entryCount: number; status: string; createdBy: string; createdAt: string };
type Run = { runKey: string; replayed: boolean; settlements: Settlement[] };
type Reconciliation = {
  groupsChecked: number;
  balanced: boolean;
  groupMismatches: { orderId: string; sellerId: string; ledgerNet: number; expected: number }[];
  settlementMismatches: { settlementId: string; amount: number; linkedAmount: number }[];
  fulfillmentGroupsWithoutLedger: number;
  totals: { currency: string; account: string; total: number }[];
};

export default function FinancePage() {
  const session = useSession();
  const admin = session ? isAdmin(session.roles) : false;
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [ledger, setLedger] = useState<LedgerPage | null>(null);
  const [page, setPage] = useState(0);
  const [orderFilter, setOrderFilter] = useState("");
  const [sellerFilter, setSellerFilter] = useState("");
  const [rates, setRates] = useState<Rates | null>(null);
  const [settlements, setSettlements] = useState<Settlement[]>([]);
  const [reconciliation, setReconciliation] = useState<Reconciliation | null>(null);
  const [rateSeller, setRateSeller] = useState("");
  const [rateBps, setRateBps] = useState("1000");
  const [settleSeller, setSettleSeller] = useState("");
  const [refund, setRefund] = useState({ orderId: "", sellerId: "", reason: "" });

  function fail(caught: unknown, fallback: string) {
    setError(caught instanceof ApiError ? caught.message : fallback);
  }

  function loadLedger(nextPage = page) {
    const query = new URLSearchParams({ page: String(nextPage), size: "20" });
    if (orderFilter.trim()) {
      query.set("orderId", orderFilter.trim());
    }
    if (sellerFilter.trim()) {
      query.set("sellerId", sellerFilter.trim());
    }
    api<LedgerPage>(`/api/backend/orders/finance/ledger?${query}`).then(setLedger).catch((caught: unknown) => fail(caught, "The ledger could not load."));
  }

  function loadRest() {
    api<Rates>("/api/backend/orders/finance/commission-rates").then(setRates).catch((caught: unknown) => fail(caught, "Rates could not load."));
    api<Settlement[]>("/api/backend/orders/finance/settlements").then(setSettlements).catch((caught: unknown) => fail(caught, "Settlements could not load."));
  }

  useEffect(() => {
    loadLedger(0);
    loadRest();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function reconcile() {
    setError(null);
    try {
      setReconciliation(await api<Reconciliation>("/api/backend/orders/finance/reconciliation"));
    } catch (caught) {
      fail(caught, "Reconciliation failed.");
    }
  }

  async function saveRate(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setMessage(null);
    try {
      await api("/api/backend/orders/finance/commission-rates", {
        method: "POST",
        body: JSON.stringify({ sellerId: rateSeller.trim(), rateBps: Number(rateBps) }),
      });
      setMessage("Commission rate recorded. It applies to orders confirmed from now on; old ledger entries are not changed.");
      loadRest();
    } catch (caught) {
      fail(caught, "The rate could not be saved.");
    }
  }

  async function settle(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setMessage(null);
    try {
      const run = await api<Run>("/api/backend/orders/finance/settlements", {
        method: "POST",
        headers: { "Idempotency-Key": crypto.randomUUID() },
        body: JSON.stringify({ sellerId: settleSeller.trim() || null }),
      });
      setMessage(run.settlements.length === 0
        ? "Nothing to settle: no seller has a positive balance on delivered orders."
        : `Created ${run.settlements.length} mock settlement(s). No real money moved.`);
      loadRest();
      loadLedger();
    } catch (caught) {
      fail(caught, "The settlement run failed.");
    }
  }

  async function refundGroup(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setMessage(null);
    if (!window.confirm("Write reversal entries for this seller's part of the order? This is a ledger adjustment only.")) {
      return;
    }
    try {
      const result = await api<{ reversedEntries: number; alreadyRefunded: boolean }>("/api/backend/orders/finance/refunds", {
        method: "POST",
        body: JSON.stringify({ orderId: refund.orderId.trim(), sellerId: refund.sellerId.trim(), reason: refund.reason.trim() }),
      });
      setMessage(result.alreadyRefunded ? "That group was already reversed. Nothing new was written." : `Wrote ${result.reversedEntries} reversal entries.`);
      loadLedger();
    } catch (caught) {
      fail(caught, "The refund adjustment failed.");
    }
  }

  return (
    <main>
      <p className={styles.kicker}>Marketplace</p>
      <h1 className={styles.title}>Finance</h1>
      <p className={styles.lede}>
        Mock accounting. The ledger is append-only and enforced by the database: a correction is a new reversal entry, never an edit.
        Settlement only groups what is owed to sellers; it does not call a payout provider or move real money.
        A refund here adjusts the ledger only; the customer payment is not refunded and stock is not restocked.
      </p>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      {message ? <p className={styles.good}>{message}</p> : null}

      <h2>Reconciliation</h2>
      <button className={styles.button} type="button" onClick={reconcile}>Run reconciliation</button>
      {reconciliation ? (
        <>
          <p>
            <span className={styles.badge}>{reconciliation.balanced ? "BALANCED" : "MISMATCH"}</span>{" "}
            {reconciliation.groupsChecked} order and seller groups checked. Groups without ledger entries: {reconciliation.fulfillmentGroupsWithoutLedger}.
          </p>
          <ul>
            {reconciliation.totals.map((total) => <li key={`${total.currency}-${total.account}`}>{total.account}: {total.total} {total.currency}</li>)}
          </ul>
          {reconciliation.groupMismatches.map((group) => (
            <p className={styles.error} key={`${group.orderId}-${group.sellerId}`}>Order {group.orderId} / {group.sellerId}: ledger {group.ledgerNet}, expected {group.expected}.</p>
          ))}
          {reconciliation.settlementMismatches.map((item) => (
            <p className={styles.error} key={item.settlementId}>Settlement {item.settlementId}: recorded {item.amount}, entries {item.linkedAmount}.</p>
          ))}
        </>
      ) : null}

      <h2>Commission</h2>
      <p className={styles.note}>Default rate {rates ? (rates.defaultRateBps / 100).toFixed(2) : "…"}%. A seller-specific rate is snapshotted on each ledger entry when an order confirms.</p>
      {admin ? (
        <form className={styles.form} onSubmit={saveRate}>
          <label>Seller subject<input value={rateSeller} onChange={(event) => setRateSeller(event.target.value)} required /></label>
          <label>Rate (basis points, 0 to 3000)<input value={rateBps} onChange={(event) => setRateBps(event.target.value)} inputMode="numeric" /></label>
          <button className={styles.button} type="submit">Set rate</button>
        </form>
      ) : null}
      <div className={styles.tableWrap}>
        <table>
          <thead><tr><th>Seller</th><th>Rate</th><th>From</th><th>By</th></tr></thead>
          <tbody>
            {rates?.rates.map((rate) => (
              <tr key={`${rate.sellerId}-${rate.effectiveFrom}`}>
                <td>{rate.sellerId}</td><td>{(rate.rateBps / 100).toFixed(2)}%</td><td>{rate.effectiveFrom}</td><td>{rate.createdBy}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h2>Mock settlements</h2>
      {admin ? (
        <form className={styles.form} onSubmit={settle}>
          <label>Seller subject (optional)<input value={settleSeller} onChange={(event) => setSettleSeller(event.target.value)} placeholder="all sellers" /></label>
          <button className={styles.button} type="submit">Run settlement</button>
        </form>
      ) : null}
      <div className={styles.tableWrap}>
        <table>
          <thead><tr><th>When</th><th>Seller</th><th>Amount</th><th>Entries</th><th>Status</th><th>By</th></tr></thead>
          <tbody>
            {settlements.map((settlement) => (
              <tr key={settlement.id}>
                <td>{settlement.createdAt}</td><td>{settlement.sellerId}</td><td>{settlement.amount} {settlement.currency}</td>
                <td>{settlement.entryCount}</td><td>{settlement.status}</td><td>{settlement.createdBy}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {admin ? (
        <>
          <h2>Refund adjustment</h2>
          <form className={styles.form} onSubmit={refundGroup}>
            <label>Order id<input value={refund.orderId} onChange={(event) => setRefund({ ...refund, orderId: event.target.value })} required /></label>
            <label>Seller subject<input value={refund.sellerId} onChange={(event) => setRefund({ ...refund, sellerId: event.target.value })} required /></label>
            <label>Reason<input value={refund.reason} onChange={(event) => setRefund({ ...refund, reason: event.target.value })} maxLength={160} required /></label>
            <button className={styles.button} type="submit">Write reversal</button>
          </form>
        </>
      ) : null}

      <h2>Ledger</h2>
      <div className={styles.row}>
        <label>Order id<input value={orderFilter} onChange={(event) => setOrderFilter(event.target.value)} /></label>
        <label>Seller<input value={sellerFilter} onChange={(event) => setSellerFilter(event.target.value)} /></label>
        <button className={styles.quiet} type="button" onClick={() => { setPage(0); loadLedger(0); }}>Filter</button>
      </div>
      <p className={styles.note}>{ledger ? `${ledger.total} entries` : "Loading the ledger."}</p>
      <div className={styles.tableWrap}>
        <table>
          <thead><tr><th>When</th><th>Order</th><th>Seller</th><th>Account</th><th>Type</th><th>Amount</th><th>Note</th></tr></thead>
          <tbody>
            {ledger?.items.map((entry) => (
              <tr key={entry.id}>
                <td>{entry.createdAt}</td>
                <td>{entry.orderId.slice(0, 8)}</td>
                <td>{entry.sellerId}</td>
                <td>{entry.account}</td>
                <td>{entry.entryType}{entry.rateBps != null ? ` (${(entry.rateBps / 100).toFixed(2)}%)` : ""}</td>
                <td>{entry.amount} {entry.currency}</td>
                <td>{entry.reason ?? ""}{entry.reversesEntryId ? ` (reverses ${entry.reversesEntryId.slice(0, 8)})` : ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {ledger && ledger.pages > 1 ? (
        <div className={styles.row}>
          <button className={styles.quiet} type="button" disabled={page === 0} onClick={() => { setPage(page - 1); loadLedger(page - 1); }}>Previous</button>
          <span>Page {page + 1} of {ledger.pages}</span>
          <button className={styles.quiet} type="button" disabled={page + 1 >= ledger.pages} onClick={() => { setPage(page + 1); loadLedger(page + 1); }}>Next</button>
        </div>
      ) : null}
    </main>
  );
}

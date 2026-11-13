"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { money } from "@/lib/money";
import type { SellerFinance } from "@/lib/types";
import styles from "../seller.module.css";

export default function EarningsPage() {
  const [finance, setFinance] = useState<SellerFinance | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api<SellerFinance>("/api/backend/orders/finance/me")
      .then((body) => {
        if (!cancelled) {
          setFinance(body);
        }
      })
      .catch((caught: unknown) => {
        if (!cancelled) {
          setError(caught instanceof Error ? caught.message : "Earnings could not be loaded.");
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (error) {
    return <p className={styles.error} role="alert">{error}</p>;
  }
  if (!finance) {
    return <p>Loading earnings.</p>;
  }

  return (
    <main>
      <p className={styles.kicker}>Earnings</p>
      <h1 className={styles.title}>Ledger and settlements</h1>
      <p className={styles.note}>
        Mock accounting only: no real money moves. Your commission rate is {(finance.commissionRateBps / 100).toFixed(2)}%.
        Entries are append-only. A refund or cancellation adds a reversal entry and never edits an old one.
        An amount becomes eligible for settlement once your part of the order is DELIVERED.
      </p>
      {finance.balances.length === 0 ? <p>No ledger entries yet. Entries appear when an order is confirmed.</p> : (
        finance.balances.map((balance) => (
          <div className={styles.cards} key={balance.currency}>
            <div className={styles.card}><span>Sales</span><strong>{money(balance.sales, balance.currency)}</strong></div>
            <div className={styles.card}><span>Seller-funded discounts</span><strong>{money(balance.discounts, balance.currency)}</strong></div>
            <div className={styles.card}><span>Commission</span><strong>{money(balance.commission, balance.currency)}</strong></div>
            <div className={styles.card}><span>Reversals</span><strong>{money(balance.reversals, balance.currency)}</strong></div>
            <div className={styles.card}><span>Net payable</span><strong>{money(balance.netPayable, balance.currency)}</strong></div>
            <div className={styles.card}><span>Settled (mock)</span><strong>{money(balance.settled, balance.currency)}</strong></div>
            <div className={styles.card}><span>Outstanding</span><strong>{money(balance.outstanding, balance.currency)}</strong></div>
            <div className={styles.card}><span>Eligible now</span><strong>{money(balance.eligibleForSettlement, balance.currency)}</strong></div>
          </div>
        ))
      )}

      <h2>Settlements</h2>
      {finance.settlements.length === 0 ? <p>No settlements yet. An administrator runs mock settlements.</p> : (
        <div className={styles.tableWrap}>
          <table>
            <thead><tr><th>When</th><th>Amount</th><th>Entries</th><th>Status</th></tr></thead>
            <tbody>
              {finance.settlements.map((settlement) => (
                <tr key={settlement.id}>
                  <td>{settlement.createdAt}</td>
                  <td>{money(settlement.amount, settlement.currency)}</td>
                  <td>{settlement.entryCount}</td>
                  <td>{settlement.status}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <h2>Recent entries</h2>
      {finance.entries.length === 0 ? <p>No entries.</p> : (
        <div className={styles.tableWrap}>
          <table>
            <thead><tr><th>When</th><th>Order</th><th>Type</th><th>Amount</th><th>Note</th></tr></thead>
            <tbody>
              {finance.entries.map((entry) => (
                <tr key={entry.id}>
                  <td>{entry.createdAt}</td>
                  <td><Link href={`/orders/${entry.orderId}`}>{entry.orderId.slice(0, 8)}</Link></td>
                  <td>{entry.entryType}{entry.rateBps != null ? ` (${(entry.rateBps / 100).toFixed(2)}%)` : ""}</td>
                  <td>{money(entry.amount, entry.currency)}</td>
                  <td>{entry.reason ?? ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </main>
  );
}

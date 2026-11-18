"use client";

import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { money } from "@/lib/money";
import styles from "../seller.module.css";

type SalesRow = {
  currency: string;
  orders: number;
  gross: string | null;
  discount: string | null;
  net: string | null;
  commission: string | null;
  commissionComplete: boolean;
};
type Report = {
  asOf: string | null;
  sellerId: string;
  orderCount: number;
  sales: SalesRow[];
  products: { productId: string; name: string | null; currency: string; units: number; revenue: string | null; revenueComplete: boolean }[];
  settlement: { available: boolean; reason: string };
};

export default function SellerAnalyticsPage() {
  const [report, setReport] = useState<Report | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api<Report>("/api/backend/analytics/sellers/me/sales")
      .then((body) => {
        if (!cancelled) {
          setReport(body);
        }
      })
      .catch((caught: unknown) => {
        if (!cancelled) {
          setError(caught instanceof ApiError ? caught.message : "Analytics could not be loaded.");
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (error) {
    return <p className={styles.error} role="alert">{error}</p>;
  }
  if (!report) {
    return <p>Loading analytics.</p>;
  }

  return (
    <main>
      <p className={styles.kicker}>Reports</p>
      <h1 className={styles.title}>Your sales</h1>
      <p className={styles.note}>
        These figures are copied from order events for {report.sellerId}. They can lag the ledger.
        {report.asOf ? ` Last apply ${report.asOf}.` : " No event has been applied yet."}
        {" "}{report.orderCount} confirmed orders include your lines.
        Commission is omitted when the event did not carry a seller snapshot. It is not shown as zero.
      </p>
      {report.sales.length === 0 ? <p>No confirmed sales yet.</p> : report.sales.map((row) => (
        <div className={styles.cards} key={row.currency}>
          <p>{row.orders} orders in {row.currency}</p>
          <p>Gross {money(row.gross, row.currency)}</p>
          <p>Discount {money(row.discount, row.currency)}</p>
          <p>Net {money(row.net, row.currency)}</p>
          <p>Commission {row.commissionComplete ? money(row.commission, row.currency) : "unavailable"}</p>
        </div>
      ))}
      <div className={styles.tableWrap}>
        <table>
          <caption>Products</caption>
          <thead><tr><th>Product</th><th>Units</th><th>Revenue</th></tr></thead>
          <tbody>
            {report.products.map((row) => (
              <tr key={`${row.productId}-${row.currency}`}>
                <td>{row.name ?? row.productId}</td>
                <td>{row.units}</td>
                <td>{row.revenueComplete ? money(row.revenue, row.currency) : "incomplete"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className={styles.note}>{report.settlement.available ? "Settlement is available." : report.settlement.reason}</p>
    </main>
  );
}

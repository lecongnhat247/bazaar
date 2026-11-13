"use client";

import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { money } from "@/lib/money";
import type { Campaign, ProductPage } from "@/lib/types";
import styles from "../seller.module.css";

export default function PromotionsPage() {
  const [campaigns, setCampaigns] = useState<Campaign[] | null>(null);
  const [products, setProducts] = useState<ProductPage["items"]>([]);
  const [productId, setProductId] = useState("");
  const [price, setPrice] = useState("");
  const [quota, setQuota] = useState("10");
  const [limit, setLimit] = useState("1");
  const [startsAt, setStartsAt] = useState("");
  const [endsAt, setEndsAt] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  async function reload() {
    const [mine, listed] = await Promise.all([
      api<ProductPage>("/api/backend/products?mine=true&size=50"),
      api<Campaign[]>("/api/backend/inventory/campaigns"),
    ]);
    setProducts(mine.items);
    setProductId((current) => current || mine.items[0]?.id || "");
    setCampaigns(listed);
  }

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      api<ProductPage>("/api/backend/products?mine=true&size=50"),
      api<Campaign[]>("/api/backend/inventory/campaigns"),
    ]).then(([mine, listed]) => {
      if (cancelled) {
        return;
      }
      setProducts(mine.items);
      setProductId(mine.items[0]?.id ?? "");
      setCampaigns(listed);
    }).catch((caught: unknown) => {
      if (!cancelled) {
        setError(caught instanceof Error ? caught.message : "Campaigns could not be loaded.");
      }
    });
    return () => {
      cancelled = true;
    };
  }, []);

  async function create(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setNotice(null);
    if (!productId || !/^\d+(\.\d{1,2})?$/.test(price) || Number(price) < 0.01) {
      setError("Choose a product and a price of at least 0.01.");
      return;
    }
    const quotaValue = Number(quota);
    const limitValue = Number(limit);
    if (!Number.isInteger(quotaValue) || quotaValue < 1 || !Number.isInteger(limitValue) || limitValue < 1) {
      setError("Quota and the per-customer limit must be whole numbers of at least 1.");
      return;
    }
    if (!startsAt || !endsAt || new Date(endsAt) <= new Date(startsAt)) {
      setError("The campaign must end after it starts.");
      return;
    }
    try {
      await api<Campaign>("/api/backend/inventory/campaigns", {
        method: "POST",
        body: JSON.stringify({
          productId,
          price,
          currency: "USD",
          quota: quotaValue,
          perCustomerLimit: limitValue,
          startsAt: new Date(startsAt).toISOString(),
          endsAt: new Date(endsAt).toISOString(),
        }),
      });
      setNotice("Campaign created as a draft.");
      await reload();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "The campaign could not be created.");
    }
  }

  async function act(campaign: Campaign, action: "schedule" | "cancel") {
    if (action === "cancel" && !window.confirm("Cancel this campaign?")) {
      return;
    }
    setError(null);
    try {
      await api(`/api/backend/inventory/campaigns/${campaign.id}/${action}`, { method: "POST" });
      setNotice(action === "schedule" ? "Campaign scheduled." : "Campaign cancelled.");
      await reload();
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : "The campaign could not be changed.");
    }
  }

  return (
    <main>
      <p className={styles.kicker}>Promotions</p>
      <h1 className={styles.title}>Flash sales</h1>
      <p className={styles.note}>
        A campaign is eligible when your store is active and you own the product stock. The price and quota are checked by inventory. Remaining quota is what can still be held. It is not revenue.
      </p>
      <form className={styles.form} onSubmit={create}>
        <label>
          Product
          <select value={productId} onChange={(event) => setProductId(event.target.value)}>
            {products.map((product) => <option key={product.id} value={product.id}>{product.name}</option>)}
          </select>
        </label>
        <label>Sale price (USD)<input value={price} onChange={(event) => setPrice(event.target.value)} inputMode="decimal" /></label>
        <label>Quota<input value={quota} onChange={(event) => setQuota(event.target.value)} inputMode="numeric" /></label>
        <label>Per customer<input value={limit} onChange={(event) => setLimit(event.target.value)} inputMode="numeric" /></label>
        <label>Starts<input type="datetime-local" value={startsAt} onChange={(event) => setStartsAt(event.target.value)} /></label>
        <label>Ends<input type="datetime-local" value={endsAt} onChange={(event) => setEndsAt(event.target.value)} /></label>
        <button className={styles.button} type="submit">Create draft campaign</button>
      </form>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      {notice ? <p className={styles.good} role="status">{notice}</p> : null}
      {!campaigns ? <p>Loading campaigns.</p> : campaigns.length === 0 ? <p>No campaigns yet.</p> : (
        <div className={styles.tableWrap}>
          <table>
            <thead>
              <tr><th>Product</th><th>Status</th><th>Price</th><th>Quota</th><th>Remaining</th><th>Limit</th><th></th></tr>
            </thead>
            <tbody>
              {campaigns.map((campaign) => (
                <tr key={campaign.id}>
                  <td>{products.find((product) => product.id === campaign.productId)?.name ?? campaign.productId}</td>
                  <td>{campaign.status}</td>
                  <td>{money(campaign.price, campaign.currency)}</td>
                  <td>{campaign.quota}</td>
                  <td>{campaign.remaining}</td>
                  <td>{campaign.perCustomerLimit}</td>
                  <td className={styles.row}>
                    {campaign.status === "DRAFT" ? <button className={styles.button} type="button" onClick={() => act(campaign, "schedule")}>Schedule</button> : null}
                    {campaign.status === "DRAFT" || campaign.status === "SCHEDULED" || campaign.status === "ACTIVE" ? (
                      <button className={styles.quiet} type="button" onClick={() => act(campaign, "cancel")}>Cancel</button>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </main>
  );
}

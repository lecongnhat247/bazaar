"use client";

import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { isAdmin } from "@/lib/roles";
import { useSession } from "@/components/Shell";
import styles from "../admin.module.css";

type Campaign = {
  id: string;
  sellerId: string;
  productId: string;
  price: number;
  currency: string;
  status: string;
  remaining: number;
  quota: number;
  startsAt: string;
  endsAt: string;
};

export default function CampaignsPage() {
  const session = useSession();
  const [campaigns, setCampaigns] = useState<Campaign[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const admin = session ? isAdmin(session.roles) : false;

  function load() {
    api<Campaign[]>("/api/backend/inventory/campaigns/monitor")
      .then(setCampaigns)
      .catch((caught: unknown) => setError(caught instanceof ApiError ? caught.message : "Campaigns could not load."));
  }

  useEffect(() => {
    load();
  }, []);

  async function act(id: string, action: "schedule" | "cancel") {
    setError(null);
    setMessage(null);
    try {
      await api(`/api/backend/inventory/campaigns/${id}/${action}`, { method: "POST" });
      setMessage(action === "schedule" ? "Campaign scheduled." : "Campaign cancelled.");
      load();
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "The campaign did not change.");
    }
  }

  return (
    <main>
      <p className={styles.kicker}>Promotions</p>
      <h1 className={styles.title}>Campaigns</h1>
      <p className={styles.lede}>The latest 50 campaigns across sellers. Schedule and cancel follow the existing inventory rules. An operator cannot change one.</p>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      {message ? <p className={styles.good}>{message}</p> : null}
      <div className={styles.tableWrap}>
        <table>
          <thead><tr><th>Status</th><th>Seller</th><th>Price</th><th>Remaining</th><th>Window</th>{admin ? <th>Action</th> : null}</tr></thead>
          <tbody>
            {campaigns.map((campaign) => (
              <tr key={campaign.id}>
                <td><span className={styles.badge}>{campaign.status}</span></td>
                <td>{campaign.sellerId}</td>
                <td>{campaign.price} {campaign.currency}</td>
                <td>{campaign.remaining}/{campaign.quota}</td>
                <td>{campaign.startsAt} → {campaign.endsAt}</td>
                {admin ? (
                  <td className={styles.row}>
                    {campaign.status === "DRAFT" ? <button className={styles.button} type="button" onClick={() => act(campaign.id, "schedule")}>Schedule</button> : null}
                    {campaign.status === "SCHEDULED" || campaign.status === "ACTIVE" ? <button className={styles.quiet} type="button" onClick={() => act(campaign.id, "cancel")}>Cancel</button> : null}
                  </td>
                ) : null}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {campaigns.length === 0 ? <p className={styles.note}>No campaigns in the latest 50.</p> : null}
    </main>
  );
}

"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { api, ApiError } from "@/lib/api";
import styles from "../../admin.module.css";

type Attempt = { type: string; resultStatus: string; createdAt: string };
type Payment = {
  id: string;
  orderId: string;
  payerId: string;
  amount: number;
  currency: string;
  status: string;
  attempts: Attempt[];
};

export default function PaymentDetailPage() {
  const params = useParams<{ id: string }>();
  const [payment, setPayment] = useState<Payment | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<Payment>(`/api/backend/payments/${params.id}`)
      .then(setPayment)
      .catch((caught: unknown) => setError(caught instanceof ApiError ? caught.message : "This payment could not load."));
  }, [params.id]);

  return (
    <main>
      <p className={styles.kicker}>Payment</p>
      <h1 className={styles.title}>{payment?.id || "Payment"}</h1>
      {error ? <p className={styles.error} role="alert">{error}</p> : null}
      {payment ? (
        <>
          <p>{payment.status} · {payment.amount} {payment.currency} · payer {payment.payerId}</p>
          <p>Order {payment.orderId}</p>
          <h2>Attempts</h2>
          <ul>
            {payment.attempts.map((attempt) => (
              <li key={`${attempt.type}-${attempt.createdAt}`}>{attempt.type} · {attempt.resultStatus} · {attempt.createdAt}</li>
            ))}
          </ul>
        </>
      ) : null}
    </main>
  );
}

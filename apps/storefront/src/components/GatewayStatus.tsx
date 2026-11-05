"use client";

import { useEffect, useState } from "react";
import styles from "./GatewayStatus.module.css";

type CheckResponse =
  | { state: "up"; gatewayUrl: string }
  | { state: "down"; gatewayUrl: string; detail: string }
  | { state: "unconfigured"; detail: string };

type View =
  | { kind: "loading" }
  | { kind: "success"; gatewayUrl: string }
  | { kind: "error"; detail: string };

export function GatewayStatus() {
  const [requestId, setRequestId] = useState(0);
  const [view, setView] = useState<View>({ kind: "loading" });

  useEffect(() => {
    const controller = new AbortController();

    async function load() {
      setView({ kind: "loading" });
      try {
        const response = await fetch("/api/gateway-health", {
          cache: "no-store",
          signal: controller.signal,
        });
        const body = (await response.json()) as CheckResponse;
        if (body.state === "up") {
          setView({ kind: "success", gatewayUrl: body.gatewayUrl });
          return;
        }
        setView({ kind: "error", detail: body.detail });
      } catch (error) {
        if (error instanceof DOMException && error.name === "AbortError") {
          return;
        }
        setView({
          kind: "error",
          detail: "The status request did not complete.",
        });
      }
    }

    void load();
    return () => controller.abort();
  }, [requestId]);

  return (
    <section id="status" className={styles.panel} aria-live="polite">
      <p className={styles.kicker}>Platform</p>
      <h2>API Gateway</h2>
      {view.kind === "loading" ? (
        <p className={styles.loading}>Checking the API Gateway…</p>
      ) : null}
      {view.kind === "success" ? (
        <p className={styles.success}>
          The API Gateway is reachable at {view.gatewayUrl}.
        </p>
      ) : null}
      {view.kind === "error" ? <p className={styles.error}>{view.detail}</p> : null}
      {view.kind !== "loading" ? (
        <button
          className={styles.button}
          type="button"
          onClick={() => setRequestId((current) => current + 1)}
        >
          Check again
        </button>
      ) : null}
    </section>
  );
}

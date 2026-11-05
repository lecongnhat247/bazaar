export type GatewayCheck =
  | { state: "up"; gatewayUrl: string }
  | { state: "down"; gatewayUrl: string; detail: string }
  | { state: "unconfigured"; detail: string };

type GatewayHealthBody = {
  status?: string;
};

const HEALTH_TIMEOUT_MS = 4000;

export function readGatewayUrl(): string | undefined {
  const value = process.env.API_GATEWAY_URL?.trim();
  if (!value) {
    return undefined;
  }
  return value.replace(/\/$/, "");
}

export async function checkGateway(): Promise<GatewayCheck> {
  const gatewayUrl = readGatewayUrl();
  if (!gatewayUrl) {
    return {
      state: "unconfigured",
      detail: "API_GATEWAY_URL is not set.",
    };
  }

  try {
    const response = await fetch(`${gatewayUrl}/actuator/health`, {
      cache: "no-store",
      signal: AbortSignal.timeout(HEALTH_TIMEOUT_MS),
    });

    if (!response.ok) {
      return {
        state: "down",
        gatewayUrl,
        detail: `The API Gateway responded with HTTP ${response.status}.`,
      };
    }

    const body = (await response.json()) as GatewayHealthBody;
    if (body.status === "UP") {
      return { state: "up", gatewayUrl };
    }

    return {
      state: "down",
      gatewayUrl,
      detail: "The API Gateway health check did not report UP.",
    };
  } catch {
    return {
      state: "down",
      gatewayUrl,
      detail: "The storefront could not reach the API Gateway.",
    };
  }
}

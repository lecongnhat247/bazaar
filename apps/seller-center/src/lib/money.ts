export function money(amount: number | string | null | undefined, currency: string | null | undefined): string {
  if (amount == null || !currency) {
    return "—";
  }
  const numeric = typeof amount === "number" ? amount : Number(amount);
  if (!Number.isFinite(numeric)) {
    return "—";
  }
  return `${numeric.toFixed(2)} ${currency}`;
}

export function priceText(amount: number | string): string {
  const numeric = typeof amount === "number" ? amount : Number(amount);
  if (!Number.isFinite(numeric)) {
    return "0.00";
  }
  return numeric.toFixed(2);
}

export function money(amount: number | string, currency: string): string {
  return `${priceText(amount)} ${currency}`;
}

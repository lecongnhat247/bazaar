export type CartLine = {
  productId: string;
  name: string;
  unitPrice: string;
  currency: string;
  quantity: number;
};

const STORAGE_KEY = "bazaar-cart";

export function addLine(lines: CartLine[], line: CartLine): CartLine[] {
  const quantity = clamp(line.quantity);
  const existing = lines.find((item) => item.productId === line.productId);
  if (!existing) {
    return [...lines, { ...line, quantity }];
  }
  return lines.map((item) =>
    item.productId === line.productId
      ? {
          ...item,
          name: line.name,
          unitPrice: line.unitPrice,
          currency: line.currency,
          quantity: clamp(item.quantity + quantity),
        }
      : item,
  );
}

export function setQuantity(lines: CartLine[], productId: string, quantity: number): CartLine[] {
  if (!Number.isInteger(quantity) || quantity < 1) {
    return lines.filter((item) => item.productId !== productId);
  }
  return lines.map((item) =>
    item.productId === productId ? { ...item, quantity: clamp(quantity) } : item,
  );
}

export function removeLine(lines: CartLine[], productId: string): CartLine[] {
  return lines.filter((item) => item.productId !== productId);
}

export function cartCount(lines: CartLine[]): number {
  return lines.reduce((sum, line) => sum + line.quantity, 0);
}

export function cartTotal(lines: CartLine[]): { amount: string; currency: string } | null {
  if (lines.length === 0) {
    return null;
  }
  const currency = lines[0].currency;
  if (lines.some((line) => line.currency !== currency)) {
    return null;
  }
  const cents = lines.reduce((sum, line) => sum + toCents(line.unitPrice) * line.quantity, 0);
  return { amount: (cents / 100).toFixed(2), currency };
}

const empty: CartLine[] = [];
let current = empty;
let hydrated = false;
const listeners = new Set<() => void>();

export function subscribeCart(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getCartSnapshot(): CartLine[] {
  if (!hydrated) {
    current = readCart();
    hydrated = true;
  }
  return current;
}

export function getServerCart(): CartLine[] {
  return empty;
}

export function addToCart(line: CartLine): void {
  publish(addLine(getCartSnapshot(), line));
}

export function changeCartQuantity(productId: string, quantity: number): void {
  publish(setQuantity(getCartSnapshot(), productId, quantity));
}

export function removeFromCart(productId: string): void {
  publish(removeLine(getCartSnapshot(), productId));
}

export function clearCart(): void {
  publish([]);
}

function publish(next: CartLine[]): void {
  current = next;
  hydrated = true;
  writeCart(next);
  listeners.forEach((listener) => listener());
}

export function readCart(): CartLine[] {
  if (typeof window === "undefined") {
    return [];
  }
  try {
    const parsed = JSON.parse(window.localStorage.getItem(STORAGE_KEY) ?? "[]") as unknown;
    if (!Array.isArray(parsed)) {
      return [];
    }
    return parsed.filter(isLine);
  } catch {
    return [];
  }
}

export function writeCart(lines: CartLine[]): void {
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(lines));
}

function isLine(value: unknown): value is CartLine {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const line = value as CartLine;
  return typeof line.productId === "string"
    && typeof line.name === "string"
    && typeof line.unitPrice === "string"
    && typeof line.currency === "string"
    && Number.isInteger(line.quantity)
    && line.quantity > 0;
}

function clamp(quantity: number): number {
  if (!Number.isInteger(quantity) || quantity < 1) {
    return 1;
  }
  return Math.min(quantity, 100);
}

function toCents(price: string): number {
  const [whole = "0", fraction = ""] = price.split(".");
  const digits = whole.replace("-", "");
  const padded = `${fraction}00`.slice(0, 2);
  const cents = Number(digits) * 100 + Number(padded);
  return whole.startsWith("-") ? -cents : cents;
}

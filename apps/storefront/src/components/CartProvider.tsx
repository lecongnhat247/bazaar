"use client";

import { createContext, useContext, useSyncExternalStore, type ReactNode } from "react";
import {
  addToCart,
  changeCartQuantity,
  clearCart,
  getCartSnapshot,
  getServerCart,
  removeFromCart,
  subscribeCart,
  type CartLine,
} from "@/lib/cart";

type CartApi = {
  lines: CartLine[];
  add: (line: CartLine) => void;
  setQuantity: (productId: string, quantity: number) => void;
  remove: (productId: string) => void;
  clear: () => void;
};

const CartContext = createContext<CartApi | null>(null);

export function CartProvider({ children }: { children: ReactNode }) {
  const lines = useSyncExternalStore(subscribeCart, getCartSnapshot, getServerCart);
  const api: CartApi = {
    lines,
    add: addToCart,
    setQuantity: changeCartQuantity,
    remove: removeFromCart,
    clear: clearCart,
  };
  return <CartContext.Provider value={api}>{children}</CartContext.Provider>;
}

export function useCart(): CartApi {
  const cart = useContext(CartContext);
  if (!cart) {
    throw new Error("CartProvider is missing.");
  }
  return cart;
}

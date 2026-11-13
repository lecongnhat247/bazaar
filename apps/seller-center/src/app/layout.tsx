import type { Metadata } from "next";
import { Fraunces, Outfit } from "next/font/google";
import { Shell } from "@/components/Shell";
import "./globals.css";

const display = Fraunces({
  subsets: ["latin"],
  variable: "--font-display",
});

const sans = Outfit({
  subsets: ["latin"],
  variable: "--font-sans",
});

export const metadata: Metadata = {
  title: "BAZAAR Seller Center",
  description: "Store, products, stock, and sales for one BAZAAR seller.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en">
      <body className={`${display.variable} ${sans.variable}`}>
        <Shell>{children}</Shell>
      </body>
    </html>
  );
}

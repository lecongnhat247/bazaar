"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { money } from "@/lib/money";
import styles from "@/app/shop.module.css";

type Item = {
  productId: string;
  name: string;
  price?: string;
  currency?: string;
};

type Home = {
  popular?: Item[];
  trending?: Item[];
  forYou?: Item[];
  recentlyViewed?: Item[];
};

export function RecommendationHome() {
  const [home, setHome] = useState<Home | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    void fetch("/api/recommendations/home", { cache: "no-store", signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) {
          return;
        }
        setHome((await response.json()) as Home);
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === "AbortError") {
          return;
        }
      });
    return () => controller.abort();
  }, []);

  if (!home) {
    return null;
  }
  return (
    <>
      <Shelf title="For you" items={home.forYou} />
      <Shelf title="Recently viewed" items={home.recentlyViewed} />
      <Shelf title="Popular" items={home.popular} />
      <Shelf title="Trending" items={home.trending} />
    </>
  );
}

export function RelatedProducts({ productId }: { productId: string }) {
  const [items, setItems] = useState<Item[] | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    void fetch(`/api/recommendations/products/${productId}/related`, { cache: "no-store", signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) {
          return;
        }
        const body = (await response.json()) as { items?: Item[] };
        setItems(body.items ?? []);
      })
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === "AbortError") {
          return;
        }
      });
    return () => controller.abort();
  }, [productId]);

  return <Shelf title="Related" items={items ?? []} />;
}

function Shelf({ title, items }: { title: string; items?: Item[] }) {
  if (!items || items.length === 0) {
    return null;
  }
  return (
    <section className={styles.page}>
      <h2 className={styles.kicker}>{title}</h2>
      <div className={styles.grid}>
        {items.map((item) => (
          <Link key={item.productId} className={styles.card} href={`/products/${item.productId}`}>
            <div className={styles.mark} aria-hidden="true">{item.name.slice(0, 1)}</div>
            <h2>{item.name}</h2>
            {item.price && item.currency ? <p className={styles.price}>{money(item.price, item.currency)}</p> : null}
          </Link>
        ))}
      </div>
    </section>
  );
}

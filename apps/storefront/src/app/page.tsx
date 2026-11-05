import { GatewayStatus } from "@/components/GatewayStatus";
import { SiteHeader } from "@/components/SiteHeader";
import styles from "./page.module.css";

export default function Home() {
  return (
    <>
      <SiteHeader />
      <main className={styles.main}>
        <section id="overview" className={styles.hero}>
          <p className={styles.kicker}>Multi-vendor marketplace</p>
          <h1>BAZAAR</h1>
          <p className={styles.lede}>
            BAZAAR is where customers discover products from independent
            sellers, keep a cart, and follow an order from checkout through
            delivery. This preview is the storefront shell: a place to confirm
            the application is running and that it can reach the API Gateway.
          </p>
        </section>

        <section id="marketplace" className={styles.market}>
          <div>
            <p className={styles.kicker}>Sellers</p>
            <h2>Marketplace</h2>
          </div>
          <div className={styles.empty}>
            <p>No products are published in this preview.</p>
          </div>
        </section>

        <GatewayStatus />
      </main>
    </>
  );
}

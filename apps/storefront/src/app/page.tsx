import { GatewayStatus } from "@/components/GatewayStatus";
import { ProductBrowser } from "@/components/ProductBrowser";
import styles from "./shop.module.css";

export default function Home() {
  return (
    <main>
      <ProductBrowser />
      <div className={styles.page}>
        <GatewayStatus />
      </div>
    </main>
  );
}

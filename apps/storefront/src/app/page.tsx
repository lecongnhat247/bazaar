import { GatewayStatus } from "@/components/GatewayStatus";
import { ProductBrowser } from "@/components/ProductBrowser";
import { RecommendationHome } from "@/components/Recommendations";
import styles from "./shop.module.css";

export default function Home() {
  return (
    <main>
      <RecommendationHome />
      <ProductBrowser />
      <div className={styles.page}>
        <GatewayStatus />
      </div>
    </main>
  );
}

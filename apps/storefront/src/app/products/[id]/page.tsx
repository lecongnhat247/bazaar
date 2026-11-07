import { ProductDetail } from "@/components/ProductDetail";

export default async function ProductPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return (
    <main>
      <ProductDetail id={id} />
    </main>
  );
}

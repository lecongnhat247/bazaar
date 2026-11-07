import { OrderDetail } from "@/components/OrderDetail";

export default async function OrderPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return (
    <main>
      <OrderDetail id={id} />
    </main>
  );
}

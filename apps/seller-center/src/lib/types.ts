export type StoreProfile = {
  status: string;
  storeName: string;
  description: string | null;
  logoUrl: string | null;
  contactEmail: string | null;
  phone: string | null;
};

export type Product = {
  id: string;
  name: string;
  description: string | null;
  sku: string;
  price: string | number;
  currency: string;
  status: "DRAFT" | "ACTIVE" | "INACTIVE" | "ARCHIVED";
  categoryId: string;
  images: { url: string; altText: string | null }[];
};

export type ProductPage = {
  items: Product[];
  page: number;
  totalItems: number;
  totalPages: number;
};

export type CategoryPage = {
  items: { id: string; name: string; slug: string }[];
};

export type Stock = {
  productId: string;
  available: number;
  reserved: number;
};

export type SaleItem = {
  productId: string;
  sellerId: string;
  sku: string;
  name: string;
  unitPrice: string | number;
  quantity: number;
  lineTotal: string | number;
  discount: string | number;
};

export type FulfillmentStatus = "PENDING" | "PACKED" | "SHIPPED" | "DELIVERED" | "CANCELLED";

export type Fulfillment = {
  id: string;
  sellerId: string;
  status: FulfillmentStatus;
  shipmentReference: string | null;
  packedAt: string | null;
  shippedAt: string | null;
  deliveredAt: string | null;
  cancelledAt: string | null;
  updatedAt: string;
  history: { from: string | null; to: string; actor: string; note: string | null; at: string }[];
};

export type SellerSale = {
  id: string;
  customerId: string;
  status: string;
  currency: string;
  sellerTotal: string | number;
  sellerDiscount: string | number;
  items: SaleItem[];
  history: { status: string; createdAt: string }[];
  createdAt: string;
  fulfillment: Fulfillment | null;
};

export type LedgerEntry = {
  id: string;
  orderId: string;
  sellerId: string;
  account: string;
  entryType: string;
  currency: string;
  amount: string | number;
  rateBps: number | null;
  reversesEntryId: string | null;
  reason: string | null;
  createdBy: string;
  createdAt: string;
};

export type Settlement = {
  id: string;
  sellerId: string;
  currency: string;
  amount: string | number;
  entryCount: number;
  status: string;
  createdBy: string;
  createdAt: string;
};

export type SellerFinance = {
  commissionRateBps: number;
  balances: {
    currency: string;
    sales: string | number;
    discounts: string | number;
    commission: string | number;
    reversals: string | number;
    netPayable: string | number;
    settled: string | number;
    outstanding: string | number;
    eligibleForSettlement: string | number;
  }[];
  entries: LedgerEntry[];
  settlements: Settlement[];
};

export type SalesPage = {
  orders: SellerSale[];
  page: number;
  totalOrders: number;
  totalPages: number;
  sellerTotal: string | number | null;
  currency: string | null;
  pendingOrders: number;
};

export type Campaign = {
  id: string;
  productId: string;
  price: string | number;
  currency: string;
  status: string;
  quota: number;
  remaining: number;
  perCustomerLimit: number;
  startsAt: string;
  endsAt: string;
};

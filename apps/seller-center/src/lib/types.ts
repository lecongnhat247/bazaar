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
};

export type SellerSale = {
  id: string;
  customerId: string;
  status: string;
  currency: string;
  sellerTotal: string | number;
  items: SaleItem[];
  history: { status: string; createdAt: string }[];
  createdAt: string;
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

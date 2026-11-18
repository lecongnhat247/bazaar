const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export const recommendationHomePath = "/api/v1/recommendations/home";

export function relatedRecommendationPath(id: string): string | null {
  if (!UUID.test(id)) {
    return null;
  }
  return `/api/v1/recommendations/products/${id}/related`;
}

export function productViewPath(id: string): string | null {
  if (!UUID.test(id)) {
    return null;
  }
  return `/api/v1/products/${id}/views`;
}

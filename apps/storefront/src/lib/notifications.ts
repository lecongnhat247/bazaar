export type LiveNotice = {
  eventId: string;
  type: string;
  orderId: string;
  summary: string;
  createdAt: string;
};

export function mergeNotices(current: LiveNotice[], incoming: LiveNotice[]): LiveNotice[] {
  const seen = new Set(current.map((item) => item.eventId));
  const next = [...current];
  for (const item of incoming) {
    if (seen.has(item.eventId)) {
      continue;
    }
    seen.add(item.eventId);
    next.push(item);
  }
  return next;
}

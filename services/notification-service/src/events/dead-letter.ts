export function failureAction(attempt: number, permanent: boolean, maxAttempts = 3): "retry" | "dead" {
  if (permanent || attempt >= maxAttempts) {
    return "dead";
  }
  return "retry";
}

export function backoffMs(attempt: number): number {
  const shift = Math.min(Math.max(attempt - 1, 0), 4);
  return Math.min(2000, 200 * (2 ** shift));
}

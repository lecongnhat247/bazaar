export function tokenRoles(accessToken: string): string[] {
  const payload = accessToken.split(".")[1];
  if (!payload) {
    return [];
  }
  try {
    const claims = JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) as {
      realm_access?: { roles?: unknown };
    };
    const roles = claims.realm_access?.roles;
    if (!Array.isArray(roles)) {
      return [];
    }
    return roles.filter((role): role is string => typeof role === "string");
  } catch {
    return [];
  }
}

export function isSeller(roles: string[]): boolean {
  return roles.includes("SELLER");
}

"""Verify a Keycloak access token.

Production tokens are RS256, checked against the realm keys. Tests set
JWT_HMAC_SECRET and then only HMAC tokens are accepted. The subject and the
realm roles come from the token. A caller cannot name a different customer.
"""

from __future__ import annotations

import time
from dataclasses import dataclass

import jwt
from jwt import PyJWK

from recommendation.settings import Settings


class AuthError(Exception):
    pass


@dataclass(frozen=True)
class Caller:
    subject: str
    roles: frozenset[str]

    def admin(self) -> bool:
        return "ADMIN" in self.roles


def caller_from_header(authorization: str | None, settings: Settings, keys: list | None = None) -> Caller | None:
    if authorization is None or authorization == "":
        return None
    if not authorization.startswith("Bearer "):
        raise AuthError("A bearer token is required.")
    token = authorization[len("Bearer "):].strip()
    if not token:
        raise AuthError("A bearer token is required.")
    claims = _claims(token, settings, keys)
    subject = claims.get("sub")
    if not isinstance(subject, str) or not subject.strip() or len(subject) > 64:
        raise AuthError("The token has no subject.")
    return Caller(subject.strip(), _roles(claims))


def _claims(token: str, settings: Settings, keys: list | None) -> dict:
    try:
        if settings.hmac_secret:
            claims = jwt.decode(
                token,
                settings.hmac_secret,
                algorithms=["HS256"],
                issuer=settings.issuer,
                options={"require": ["exp", "sub", "iss"], "verify_aud": False},
                leeway=30,
            )
        else:
            claims = jwt.decode(
                token,
                _key(token, settings, keys),
                algorithms=["RS256"],
                issuer=settings.issuer,
                options={"require": ["exp", "sub", "iss"], "verify_aud": False},
                leeway=30,
            )
    except jwt.PyJWTError as error:
        raise AuthError("The token was rejected.") from error
    if not isinstance(claims, dict):
        raise AuthError("The token was rejected.")
    return claims


def _key(token: str, settings: Settings, keys: list | None):
    header = jwt.get_unverified_header(token)
    kid = header.get("kid") if isinstance(header, dict) else None
    if not isinstance(kid, str):
        raise AuthError("The token was rejected.")
    for key in keys if keys is not None else _jwks(settings):
        if getattr(key, "key_id", None) == kid:
            return key
    raise AuthError("The token was rejected.")


_cached: tuple[float, list] | None = None


def _jwks(settings: Settings) -> list:
    global _cached
    if not settings.jwks_uri:
        raise AuthError("The token was rejected.")
    now = time.monotonic()
    if _cached and now - _cached[0] < 300:
        return _cached[1]
    import urllib.request

    try:
        with urllib.request.urlopen(settings.jwks_uri, timeout=2) as response:
            payload = response.read()
    except Exception as error:
        raise AuthError("The token was rejected.") from error
    keys = _parse_jwks(payload)
    _cached = (now, keys)
    return keys


def _parse_jwks(payload: bytes) -> list:
    import json

    body = json.loads(payload)
    values = body.get("keys") if isinstance(body, dict) else None
    if not isinstance(values, list):
        raise AuthError("The token was rejected.")
    return [PyJWK.from_dict(key) for key in values if isinstance(key, dict)]


def _roles(claims: dict) -> frozenset[str]:
    realm = claims.get("realm_access")
    if not isinstance(realm, dict):
        return frozenset()
    roles = realm.get("roles")
    if not isinstance(roles, list):
        return frozenset()
    return frozenset(role for role in roles if isinstance(role, str))

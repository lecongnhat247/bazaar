import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    database_url: str
    kafka_brokers: str
    catalog_topic: str
    order_topic: str
    dlq_topic: str
    group_id: str
    issuer: str
    jwks_uri: str
    hmac_secret: str


def load_settings() -> Settings:
    return Settings(
        database_url=os.environ.get("DATABASE_URL", ""),
        kafka_brokers=os.environ.get("KAFKA_BROKERS", ""),
        catalog_topic=os.environ.get("CATALOG_EVENTS_TOPIC", "bazaar.catalog.events"),
        order_topic=os.environ.get("ORDER_EVENTS_TOPIC", "bazaar.order.events"),
        dlq_topic=os.environ.get("DLQ_TOPIC", "bazaar.dlq"),
        group_id=os.environ.get("KAFKA_GROUP", "recommendation-service"),
        issuer=os.environ.get("KEYCLOAK_ISSUER", "http://localhost:8088/realms/bazaar"),
        jwks_uri=os.environ.get("KEYCLOAK_JWKS_URI", ""),
        hmac_secret=os.environ.get("JWT_HMAC_SECRET", ""),
    )

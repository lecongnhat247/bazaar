"""Consume catalog and order facts.

A bad payload is skipped and its offset is committed. A store failure is
retried a few times and then copied to the dead-letter topic. Offsets commit
after that. This is at-least-once. It is not exactly-once.
"""

from __future__ import annotations

import asyncio
import json
import logging

from recommendation.settings import Settings
from recommendation.store import MemoryStore, PostgresStore

log = logging.getLogger("recommendation.consumer")
Store = MemoryStore | PostgresStore


def handle(raw: bytes | str, store: Store) -> str:
    try:
        event = json.loads(raw)
    except (json.JSONDecodeError, UnicodeDecodeError):
        return "invalid"
    if not isinstance(event, dict):
        return "invalid"
    return store.apply(event)


def dead_letter(topic: str, raw: str, error: str) -> dict:
    event_id = None
    correlation_id = None
    try:
        body = json.loads(raw)
    except json.JSONDecodeError:
        body = None
    if isinstance(body, dict):
        event_id = body.get("eventId") if isinstance(body.get("eventId"), str) else None
        correlation_id = body.get("correlationId") if isinstance(body.get("correlationId"), str) else None
    return {
        "originalTopic": topic,
        "eventId": event_id,
        "correlationId": correlation_id,
        "error": error[:500],
        "value": raw[:8000],
    }


async def run_consumer(settings: Settings, store: Store, stop: asyncio.Event) -> None:
    from aiokafka import AIOKafkaConsumer, AIOKafkaProducer

    while not stop.is_set():
        consumer = AIOKafkaConsumer(
            settings.catalog_topic,
            settings.order_topic,
            bootstrap_servers=settings.kafka_brokers,
            group_id=settings.group_id,
            enable_auto_commit=False,
            auto_offset_reset="earliest",
        )
        producer = AIOKafkaProducer(bootstrap_servers=settings.kafka_brokers)
        try:
            await consumer.start()
            await producer.start()
            while not stop.is_set():
                batches = await consumer.getmany(timeout_ms=1000, max_records=20)
                for messages in batches.values():
                    for message in messages:
                        await _one(consumer, producer, settings, store, message)
        except asyncio.CancelledError:
            raise
        except Exception as error:
            log.warning("Recommendation consumer will retry: %s", error)
            await asyncio.sleep(5)
        finally:
            await _stop(consumer)
            await _stop(producer)


async def _one(consumer, producer, settings: Settings, store: Store, message) -> None:
    raw = message.value.decode("utf-8", errors="replace")
    for attempt in range(5):
        try:
            handle(message.value, store)
            break
        except Exception as error:
            if attempt == 4:
                letter = dead_letter(message.topic, raw, str(error))
                await producer.send_and_wait(settings.dlq_topic, json.dumps(letter).encode("utf-8"), key=message.key)
                log.warning("Recommendation event was copied to the dead letter topic: %s", letter["eventId"])
                break
            await asyncio.sleep(min(2 ** attempt, 8))
    await consumer.commit()


async def _stop(client) -> None:
    try:
        await client.stop()
    except Exception:
        log.warning("Recommendation Kafka client did not stop cleanly")

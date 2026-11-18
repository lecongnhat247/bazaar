"""Persist applied envelopes and fold them on read.

Postgres is the durable log. The API folds that log under a lock. A second
copy of an event id inserts nothing. Rebuild folds the same rows again.
An empty DATABASE_URL uses an in-memory log for tests.
"""

from __future__ import annotations

import json
import threading
from pathlib import Path

from recommendation.model import State, ingest, parse_time, project


class MemoryStore:
    def __init__(self) -> None:
        self._events: list[dict] = []
        self._state = State()
        self._lock = threading.Lock()

    def apply(self, event: dict) -> str:
        with self._lock:
            result = ingest(self._state, event)
            if result == "applied":
                self._events.append(event)
            return result

    def state(self) -> State:
        with self._lock:
            return project(list(self._events))

    def rebuild(self) -> int:
        with self._lock:
            self._state = project(list(self._events))
            return len(self._events)

    def ready(self) -> bool:
        return True


class PostgresStore:
    def __init__(self, database_url: str) -> None:
        import psycopg

        self._lock = threading.Lock()
        self._conn = psycopg.connect(database_url, autocommit=False)
        schema = Path(__file__).resolve().parents[2].joinpath("schema.sql").read_text(encoding="utf-8")
        with self._lock:
            self._conn.execute(schema)
            self._conn.commit()
            self._state = project(self._load())

    def apply(self, event: dict) -> str:
        with self._lock:
            result = ingest(self._state, event)
            if result != "applied":
                return result
            try:
                self._conn.execute(
                    """
                    INSERT INTO recommendation_event (event_id, occurred_at, body)
                    VALUES (%s, %s, %s::jsonb)
                    ON CONFLICT (event_id) DO NOTHING
                    """,
                    (event["eventId"], parse_time(event["occurredAt"]), json.dumps(event)),
                )
                self._conn.commit()
            except Exception:
                self._conn.rollback()
                self._state = project(self._load())
                raise
            return "applied"

    def state(self) -> State:
        with self._lock:
            return project(self._load())

    def rebuild(self) -> int:
        with self._lock:
            events = self._load()
            self._state = project(events)
            return len(events)

    def ready(self) -> bool:
        try:
            with self._lock:
                self._conn.execute("SELECT 1")
            return True
        except Exception:
            return False

    def _load(self) -> list[dict]:
        rows = self._conn.execute(
            "SELECT body FROM recommendation_event ORDER BY occurred_at, event_id"
        ).fetchall()
        events = []
        for (body,) in rows:
            events.append(body if isinstance(body, dict) else json.loads(body))
        return events

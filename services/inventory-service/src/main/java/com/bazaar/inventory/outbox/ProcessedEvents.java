package com.bazaar.inventory.outbox;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.bazaar.inventory.messaging.EventEnvelope;

@Component
public class ProcessedEvents {

    private final JdbcTemplate jdbc;

    public ProcessedEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean record(String consumerName, EventEnvelope event) {
        int inserted = jdbc.update("""
                INSERT INTO processed_events (event_id, consumer_name, event_type, processed_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, event.eventId(), consumerName, event.eventType(), Timestamp.from(Instant.now()));
        return inserted == 1;
    }
}

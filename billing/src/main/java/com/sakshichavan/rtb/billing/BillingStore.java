package com.sakshichavan.rtb.billing;

import com.sakshichavan.rtb.common.events.SettlementEvent;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The billing ledger in Postgres. Each batch of settlements is written in ONE transaction together with the
 * consumer's next Kafka offsets, so events and read position always commit or roll back together.
 * The event_id primary key is a final safety net: a duplicate that slips past every upstream layer is ignored.
 */
public final class BillingStore implements AutoCloseable {

    static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS settlements (
                event_id        TEXT PRIMARY KEY,
                bid_id          TEXT NOT NULL,
                campaign_id     TEXT NOT NULL,
                type            TEXT NOT NULL CHECK (type IN ('WIN', 'LOSS', 'EXPIRED')),
                reserved_micros BIGINT NOT NULL CHECK (reserved_micros >= 0),
                charged_micros  BIGINT NOT NULL CHECK (charged_micros >= 0 AND charged_micros <= reserved_micros),
                occurred_at     TIMESTAMPTZ NOT NULL,
                recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            CREATE INDEX IF NOT EXISTS settlements_by_campaign ON settlements (campaign_id);
            CREATE TABLE IF NOT EXISTS consumer_offsets (
                consumer        TEXT NOT NULL,
                topic           TEXT NOT NULL,
                kafka_partition INT NOT NULL,
                next_offset     BIGINT NOT NULL,
                PRIMARY KEY (consumer, topic, kafka_partition)
            );
            CREATE OR REPLACE VIEW campaign_spend AS
                SELECT campaign_id,
                       count(*) FILTER (WHERE type = 'WIN') AS wins,
                       count(*) AS settlements,
                       sum(charged_micros) AS spent_micros
                FROM settlements
                GROUP BY campaign_id;
            """;

    private static final String INSERT_SETTLEMENT = """
            INSERT INTO settlements (event_id, bid_id, campaign_id, type, reserved_micros, charged_micros, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING""";

    private static final String UPSERT_OFFSET = """
            INSERT INTO consumer_offsets (consumer, topic, kafka_partition, next_offset)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (consumer, topic, kafka_partition) DO UPDATE SET next_offset = EXCLUDED.next_offset""";

    private final Connection connection;
    private final Runnable beforeCommit;

    public BillingStore(String jdbcUrl, String user, String password) throws SQLException {
        this(jdbcUrl, user, password, () -> {
        });
    }

    /** @param beforeCommit test hook that runs just before each commit; throwing from it simulates a crash */
    BillingStore(String jdbcUrl, String user, String password, Runnable beforeCommit) throws SQLException {
        this.connection = DriverManager.getConnection(jdbcUrl, user, password);
        this.connection.setAutoCommit(false);
        this.beforeCommit = beforeCommit;
        try (Statement statement = connection.createStatement()) {
            statement.execute(SCHEMA);
        }
        connection.commit();
    }

    /**
     * Records a batch atomically: the settlements and the next offset for each partition.
     * @return how many settlements were new (duplicates are ignored)
     */
    public int record(String consumer, String topic, List<SettlementEvent> events, Map<Integer, Long> nextOffsets)
            throws SQLException {
        try {
            int inserted = 0;
            if (!events.isEmpty()) {
                try (PreparedStatement insert = connection.prepareStatement(INSERT_SETTLEMENT)) {
                    for (SettlementEvent event : events) {
                        insert.setString(1, event.eventId());
                        insert.setString(2, event.bidId());
                        insert.setString(3, event.campaignId());
                        insert.setString(4, event.type().name());
                        insert.setLong(5, event.reservedMicros());
                        insert.setLong(6, event.chargedMicros());
                        insert.setObject(7, OffsetDateTime.ofInstant(
                                Instant.ofEpochMilli(event.occurredAtMillis()), ZoneOffset.UTC));
                        insert.addBatch();
                    }
                    for (int count : insert.executeBatch()) {
                        if (count > 0) {
                            inserted += count;
                        }
                    }
                }
            }
            try (PreparedStatement upsert = connection.prepareStatement(UPSERT_OFFSET)) {
                for (Map.Entry<Integer, Long> offset : nextOffsets.entrySet()) {
                    upsert.setString(1, consumer);
                    upsert.setString(2, topic);
                    upsert.setInt(3, offset.getKey());
                    upsert.setLong(4, offset.getValue());
                    upsert.addBatch();
                }
                upsert.executeBatch();
            }
            beforeCommit.run();
            connection.commit();
            return inserted;
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly(e);
            throw e;
        }
    }

    /** The next offset to read for each partition, as last committed by this consumer. */
    public Map<Integer, Long> loadOffsets(String consumer, String topic) throws SQLException {
        Map<Integer, Long> offsets = new HashMap<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT kafka_partition, next_offset FROM consumer_offsets WHERE consumer = ? AND topic = ?")) {
            query.setString(1, consumer);
            query.setString(2, topic);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    offsets.put(rows.getInt(1), rows.getLong(2));
                }
            }
        }
        connection.commit();
        return offsets;
    }

    public BillingTotals totals() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("""
                     SELECT count(*), count(*) FILTER (WHERE type = 'WIN'), coalesce(sum(charged_micros), 0)
                     FROM settlements""")) {
            row.next();
            BillingTotals totals = new BillingTotals(row.getLong(1), row.getLong(2), row.getLong(3));
            connection.commit();
            return totals;
        }
    }

    private void rollbackQuietly(Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}

package com.sakshichavan.rtb.billing;

import com.sakshichavan.rtb.common.events.SettlementEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class BillingStoreTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static BillingStore store(Runnable beforeCommit) throws SQLException {
        return new BillingStore(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), beforeCommit);
    }

    @BeforeEach
    void emptyTheLedger() throws SQLException {
        try (BillingStore schemaReady = store(() -> {
        });
             Connection connection = DriverManager.getConnection(
                     POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE settlements, consumer_offsets");
        }
    }

    @Test
    void recordsSettlementsAndOffsetsTogether() throws SQLException {
        try (BillingStore store = store(() -> {
        })) {
            int inserted = store.record("billing", "t", List.of(
                    SettlementEvent.win("b1", "c1", 2_000, 1_000, 1L),
                    SettlementEvent.loss("b2", "c1", 1_500, 2L)), Map.of(0, 2L));

            assertEquals(2, inserted);
            assertEquals(new BillingTotals(2, 1, 1_000), store.totals());
            assertEquals(Map.of(0, 2L), store.loadOffsets("billing", "t"));
        }
    }

    @Test
    void duplicateEventIdsAreIgnored() throws SQLException {
        try (BillingStore store = store(() -> {
        })) {
            store.record("billing", "t", List.of(SettlementEvent.win("b1", "c1", 2_000, 1_000, 1L)), Map.of(0, 1L));

            int inserted = store.record("billing", "t", List.of(
                    SettlementEvent.win("b1", "c1", 2_000, 1_000, 1L),
                    SettlementEvent.loss("b2", "c1", 1_500, 2L)), Map.of(0, 3L));

            assertEquals(1, inserted);
            assertEquals(new BillingTotals(2, 1, 1_000), store.totals());
        }
    }

    @Test
    void aCrashBeforeCommitLeavesNoTrace() throws SQLException {
        try (BillingStore crashing = store(() -> {
            throw new IllegalStateException("simulated crash before commit");
        })) {
            assertThrows(IllegalStateException.class, () -> crashing.record("billing", "t",
                    List.of(SettlementEvent.win("b1", "c1", 2_000, 1_000, 1L)), Map.of(0, 1L)));
        }
        try (BillingStore store = store(() -> {
        })) {
            assertEquals(new BillingTotals(0, 0, 0), store.totals());
            assertTrue(store.loadOffsets("billing", "t").isEmpty());
        }
    }
}

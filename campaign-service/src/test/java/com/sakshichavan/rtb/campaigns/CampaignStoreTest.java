package com.sakshichavan.rtb.campaigns;

import com.sakshichavan.rtb.common.campaigns.CampaignSpec;
import com.sakshichavan.rtb.common.campaigns.CampaignStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
class CampaignStoreTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static CampaignStore store;

    @BeforeAll
    static void openStore() {
        store = new CampaignStore(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @BeforeEach
    void emptyTables() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE campaigns, campaign_changes RESTART IDENTITY");
        }
    }

    private static CampaignSpec campaign(String id, double price) {
        return new CampaignSpec(id, "cr-1", price, 10.0, null, null, CampaignStatus.ACTIVE);
    }

    @Test
    void createThenUpdateIncrementsTheVersionAndRecordsEveryChange() {
        assertEquals(1, store.create(campaign("c-one", 1.00)).version());
        assertEquals(2, store.update(campaign("c-one", 1.50), 1).version());

        assertEquals(1.50, store.get("c-one").orElseThrow().campaign().maxBidCpm());
        List<CampaignChange> changes = store.changesAfter(0, 100);
        assertEquals(List.of(1L, 2L), changes.stream().map(CampaignChange::version).toList());
        assertEquals(1.00, changes.get(0).campaign().maxBidCpm(), "the log keeps every past state");
    }

    @Test
    void aStaleVersionIsRejectedAndReportsTheCurrentOne() {
        store.create(campaign("c-one", 1.00));
        store.update(campaign("c-one", 1.50), 1);

        CampaignStore.VersionMismatch mismatch = assertThrows(CampaignStore.VersionMismatch.class,
                () -> store.update(campaign("c-one", 9.99), 1));

        assertEquals(2, mismatch.currentVersion());
        assertEquals(1.50, store.get("c-one").orElseThrow().campaign().maxBidCpm(), "the stale write changed nothing");
        assertEquals(2, store.changesAfter(0, 100).size(), "and recorded nothing");
    }

    @Test
    void creatingTwiceOrUpdatingAMissingCampaignFails() {
        store.create(campaign("c-one", 1.00));

        assertThrows(CampaignStore.AlreadyExists.class, () -> store.create(campaign("c-one", 2.00)));
        assertThrows(CampaignStore.NotFound.class, () -> store.update(campaign("c-missing", 2.00), 1));
    }

    @Test
    void concurrentEditsOfTheSameVersionLetExactlyOneWin() throws Exception {
        store.create(campaign("c-one", 1.00));
        int editors = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(editors)) {
            for (int i = 0; i < editors; i++) {
                double price = 2.00 + i;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        store.update(campaign("c-one", price), 1);
                        return true;
                    } catch (CampaignStore.VersionMismatch e) {
                        return false;
                    }
                }));
            }
            start.countDown();
            long winners = 0;
            for (Future<Boolean> result : results) {
                winners += result.get() ? 1 : 0;
            }
            assertEquals(1, winners, "exactly one editor may win; the rest must see a conflict");
        }
        assertEquals(2, store.get("c-one").orElseThrow().version());
    }

    @Test
    void concurrentWritesProduceAGapFreeOrderedChangeLog() throws Exception {
        int writers = 20;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                String id = "c-" + String.format("%03d", i);
                results.add(pool.submit(() -> {
                    start.await();
                    store.create(campaign(id, 1.00));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get();
            }
        }
        List<Long> revisions = store.changesAfter(0, 100).stream().map(CampaignChange::revision).toList();

        assertEquals(writers, revisions.size());
        for (int i = 0; i < revisions.size(); i++) {
            assertEquals(i + 1, revisions.get(i), "revisions must be 1..n with no gaps");
        }
    }
}

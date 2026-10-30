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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers
class OutboxRelayTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static CampaignStore store;

    /** Records what it publishes; can be told to fail the next batch. */
    private static final class FakePublisher implements ChangePublisher {
        final List<CampaignChange> received = new CopyOnWriteArrayList<>();
        volatile boolean failNext;

        @Override
        public void publish(List<CampaignChange> changes) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("kafka unavailable");
            }
            received.addAll(changes);
        }

        List<Long> revisions() {
            return received.stream().map(CampaignChange::revision).toList();
        }
    }

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
            statement.execute("TRUNCATE campaigns, campaign_changes, outbox_position RESTART IDENTITY");
        }
    }

    private static CampaignSpec campaign(String id, double price) {
        return new CampaignSpec(id, "cr-1", price, 10.0, null, null, CampaignStatus.ACTIVE);
    }

    private static OutboxRelay relay(ChangePublisher publisher) {
        return new OutboxRelay(store, publisher, 500, Duration.ofMillis(200));
    }

    @Test
    void publishesEveryChangeOnceInRevisionOrder() throws Exception {
        store.create(campaign("c-a", 1.00));
        store.update(campaign("c-a", 1.50), 1);
        store.create(campaign("c-b", 2.00));
        FakePublisher publisher = new FakePublisher();
        OutboxRelay relay = relay(publisher);

        assertEquals(3, relay.runOnce());
        assertEquals(0, relay.runOnce(), "nothing new: nothing republished");

        assertEquals(List.of(1L, 2L, 3L), publisher.revisions());
        assertEquals(new OutboxStatus(OutboxRelay.PUBLISHER, 3, 3, 0), store.outboxStatus(OutboxRelay.PUBLISHER));
    }

    @Test
    void aFailedPublishLeavesThePositionUnchangedAndIsRetried() throws Exception {
        store.create(campaign("c-a", 1.00));
        FakePublisher publisher = new FakePublisher();
        publisher.failNext = true;
        OutboxRelay relay = relay(publisher);

        assertThrows(IllegalStateException.class, relay::runOnce);
        assertEquals(0, store.publishedRevision(OutboxRelay.PUBLISHER), "a failed publish must not advance");

        assertEquals(1, relay.runOnce());
        assertEquals(List.of(1L), publisher.revisions(), "the change is delivered on the retry");
        assertEquals(1, store.publishedRevision(OutboxRelay.PUBLISHER));
    }

    @Test
    void aRestartedRelayResumesFromItsStoredPosition() throws Exception {
        store.create(campaign("c-a", 1.00));
        store.create(campaign("c-b", 1.00));
        relay(new FakePublisher()).runOnce();

        store.create(campaign("c-c", 1.00));
        FakePublisher afterRestart = new FakePublisher();
        relay(afterRestart).runOnce();

        assertEquals(List.of(3L), afterRestart.revisions(), "only the change made after the last published position");
    }
}

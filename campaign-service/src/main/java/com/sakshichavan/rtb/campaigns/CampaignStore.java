package com.sakshichavan.rtb.campaigns;

import com.sakshichavan.rtb.common.campaigns.CampaignSpec;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Campaigns in Postgres. Every write updates the campaign and appends its full new state to campaign_changes in one
 * transaction: an audit log, and the outbox that change propagation reads from.
 *
 * Writers take a transaction-scoped advisory lock, so changes commit one at a time in revision order. Without it,
 * two writers could take revisions 5 and 6, commit 6 first, and a reader asking for "everything after 5" would
 * move past 5 before it committed and never see it. Serializing is cheap for a control plane's write rate.
 */
public final class CampaignStore implements AutoCloseable {

    static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS campaigns (
                id         TEXT PRIMARY KEY,
                version    BIGINT NOT NULL,
                status     TEXT NOT NULL,
                spec       JSONB NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            CREATE TABLE IF NOT EXISTS campaign_changes (
                revision    BIGSERIAL PRIMARY KEY,
                campaign_id TEXT NOT NULL,
                version     BIGINT NOT NULL,
                spec        JSONB NOT NULL,
                changed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                UNIQUE (campaign_id, version)
            );
            """;

    private static final long WRITE_LOCK_KEY = 7_240_001L;

    /** The campaign already exists, so it cannot be created. */
    public static final class AlreadyExists extends RuntimeException {
        AlreadyExists(String id) {
            super("campaign already exists: " + id);
        }
    }

    public static final class NotFound extends RuntimeException {
        NotFound(String id) {
            super("campaign not found: " + id);
        }
    }

    /** Someone else changed the campaign since the caller read it. */
    public static final class VersionMismatch extends RuntimeException {
        private final long currentVersion;

        VersionMismatch(String id, long currentVersion) {
            super("campaign " + id + " is at version " + currentVersion);
            this.currentVersion = currentVersion;
        }

        public long currentVersion() {
            return currentVersion;
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private final HikariDataSource dataSource;

    public CampaignStore(String jdbcUrl, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(8);
        config.setPoolName("campaign-store");
        this.dataSource = new HikariDataSource(config);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(SCHEMA);
        } catch (SQLException e) {
            dataSource.close();
            throw new IllegalStateException("cannot create the campaign schema", e);
        }
    }

    public List<VersionedCampaign> list() {
        return read(connection -> {
            List<VersionedCampaign> campaigns = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT spec, version FROM campaigns ORDER BY id");
                 ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    campaigns.add(row(rows));
                }
            }
            return campaigns;
        });
    }

    public Optional<VersionedCampaign> get(String id) {
        return read(connection -> {
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT spec, version FROM campaigns WHERE id = ?")) {
                query.setString(1, id);
                try (ResultSet rows = query.executeQuery()) {
                    return rows.next() ? Optional.of(row(rows)) : Optional.empty();
                }
            }
        });
    }

    public VersionedCampaign create(CampaignSpec campaign) {
        return write(connection -> {
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO campaigns (id, version, status, spec) VALUES (?, 1, ?, ?::jsonb)
                    ON CONFLICT (id) DO NOTHING""")) {
                insert.setString(1, campaign.id());
                insert.setString(2, campaign.status().name());
                insert.setString(3, CampaignJson.write(campaign));
                if (insert.executeUpdate() == 0) {
                    throw new AlreadyExists(campaign.id());
                }
            }
            recordChange(connection, campaign, 1);
            return new VersionedCampaign(campaign, 1);
        });
    }

    /** Applies the change only if the campaign is still at expectedVersion. */
    public VersionedCampaign update(CampaignSpec campaign, long expectedVersion) {
        return write(connection -> {
            long newVersion;
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE campaigns SET version = version + 1, status = ?, spec = ?::jsonb, updated_at = now()
                    WHERE id = ? AND version = ?
                    RETURNING version""")) {
                update.setString(1, campaign.status().name());
                update.setString(2, CampaignJson.write(campaign));
                update.setString(3, campaign.id());
                update.setLong(4, expectedVersion);
                try (ResultSet rows = update.executeQuery()) {
                    if (!rows.next()) {
                        throw currentState(connection, campaign.id());
                    }
                    newVersion = rows.getLong(1);
                }
            }
            recordChange(connection, campaign, newVersion);
            return new VersionedCampaign(campaign, newVersion);
        });
    }

    /** Changes with a revision greater than the given one, oldest first. */
    public List<CampaignChange> changesAfter(long revision, int limit) {
        return read(connection -> {
            List<CampaignChange> changes = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT revision, campaign_id, version, spec FROM campaign_changes
                    WHERE revision > ? ORDER BY revision LIMIT ?""")) {
                query.setLong(1, revision);
                query.setInt(2, limit);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        changes.add(new CampaignChange(rows.getLong(1), rows.getString(2), rows.getLong(3),
                                CampaignJson.readStored(rows.getString(4), CampaignSpec.class)));
                    }
                }
            }
            return changes;
        });
    }

    /** Explains why an update matched no row: the campaign is missing, or it has moved to another version. */
    private static RuntimeException currentState(Connection connection, String id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT version FROM campaigns WHERE id = ?")) {
            query.setString(1, id);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? new VersionMismatch(id, rows.getLong(1)) : new NotFound(id);
            }
        }
    }

    private static void recordChange(Connection connection, CampaignSpec campaign, long version) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO campaign_changes (campaign_id, version, spec) VALUES (?, ?, ?::jsonb)")) {
            insert.setString(1, campaign.id());
            insert.setLong(2, version);
            insert.setString(3, CampaignJson.write(campaign));
            insert.executeUpdate();
        }
    }

    private static VersionedCampaign row(ResultSet rows) throws SQLException {
        return new VersionedCampaign(CampaignJson.readStored(rows.getString("spec"), CampaignSpec.class),
                rows.getLong("version"));
    }

    private <T> T read(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            return work.run(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("campaign database error", e);
        }
    }

    /** Runs the work in one transaction, holding the write lock, so writes commit one at a time in revision order. */
    private <T> T write(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                    lock.setLong(1, WRITE_LOCK_KEY);
                    lock.execute();
                }
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("campaign database error", e);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}

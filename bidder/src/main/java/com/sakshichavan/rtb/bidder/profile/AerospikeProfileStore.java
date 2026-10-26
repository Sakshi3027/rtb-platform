package com.sakshichavan.rtb.bidder.profile;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.AerospikeException;
import com.aerospike.client.Bin;
import com.aerospike.client.Host;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.async.EventLoops;
import com.aerospike.client.async.EventPolicy;
import com.aerospike.client.async.NioEventLoops;
import com.aerospike.client.listener.RecordListener;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.Policy;
import com.aerospike.client.policy.WritePolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * User profiles in Aerospike, one record per user: key = user id, bin "segments" = list of segment names.
 * Reads use the async API on Aerospike's own event loop, so bidder threads never block.
 * The read timeout matches the bid pipeline's budget, with no retries: a late profile is worthless.
 *
 * Concurrency is bounded: at most MAX_IN_FLIGHT commands use connections at once, and bursts wait in a
 * bounded queue instead of being rejected. Commands that wait too long hit the pipeline's deadline;
 * if the queue itself fills, Aerospike rejects immediately and the pipeline bids without a profile.
 */
public final class AerospikeProfileStore implements ProfileStore {

    static final String SET = "profiles";
    static final String SEGMENTS_BIN = "segments";

    private static final int EVENT_LOOPS = 1;
    private static final int MAX_IN_FLIGHT = 100;
    private static final int MAX_QUEUED = 5_000;
    private static final long RETRY_PAUSE_MILLIS = 500;

    private final EventLoops eventLoops;
    private final AerospikeClient client;
    private final String namespace;
    private final Policy readPolicy;
    private final WritePolicy writePolicy;

    public AerospikeProfileStore(String host, int port, String namespace, int readTimeoutMillis) {
        EventPolicy eventPolicy = new EventPolicy();
        eventPolicy.maxCommandsInProcess = MAX_IN_FLIGHT;
        eventPolicy.maxCommandsInQueue = MAX_QUEUED;
        EventLoops loops = new NioEventLoops(eventPolicy, EVENT_LOOPS);

        ClientPolicy clientPolicy = new ClientPolicy();
        clientPolicy.eventLoops = loops;
        clientPolicy.asyncMaxConnsPerNode = MAX_IN_FLIGHT * EVENT_LOOPS;
        clientPolicy.timeout = 2_000;
        try {
            this.client = new AerospikeClient(clientPolicy, new Host(host, port));
        } catch (RuntimeException e) {
            loops.close();
            throw e;
        }
        this.eventLoops = loops;
        this.namespace = namespace;

        this.readPolicy = new Policy();
        readPolicy.socketTimeout = readTimeoutMillis;
        readPolicy.totalTimeout = readTimeoutMillis;
        readPolicy.maxRetries = 0;

        this.writePolicy = new WritePolicy();
        writePolicy.totalTimeout = 1_000;
    }

    /**
     * Connects, retrying while Aerospike is still starting up. Aerospike logs "service ready" slightly
     * before its partitions are initialized, so a client that starts alongside it (in Compose, Kubernetes,
     * or a test container) can briefly see "not yet fully initialized".
     */
    public static AerospikeProfileStore connectWithRetry(String host, int port, String namespace,
                                                         int readTimeoutMillis, Duration maxWait)
            throws InterruptedException {
        long deadline = System.nanoTime() + maxWait.toNanos();
        while (true) {
            try {
                return new AerospikeProfileStore(host, port, namespace, readTimeoutMillis);
            } catch (AerospikeException e) {
                if (System.nanoTime() - deadline >= 0) {
                    throw e;
                }
                Thread.sleep(RETRY_PAUSE_MILLIS);
            }
        }
    }

    @Override
    public CompletionStage<UserProfile> lookup(String userId) {
        CompletableFuture<UserProfile> result = new CompletableFuture<>();
        try {
            client.get(eventLoops.next(), new RecordListener() {
                @Override
                public void onSuccess(Key key, Record record) {
                    result.complete(record == null ? UserProfile.UNKNOWN : toProfile(record));
                }

                @Override
                public void onFailure(AerospikeException error) {
                    result.completeExceptionally(error);
                }
            }, readPolicy, key(userId));
        } catch (AerospikeException e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    /** Synchronous write, for seeding data. Not used on the bid path. */
    public void put(String userId, UserProfile profile) {
        client.put(writePolicy, key(userId), new Bin(SEGMENTS_BIN, new ArrayList<>(profile.segments())));
    }

    private Key key(String userId) {
        return new Key(namespace, SET, userId);
    }

    private static UserProfile toProfile(Record record) {
        List<?> stored = record.getList(SEGMENTS_BIN);
        if (stored == null) {
            return UserProfile.UNKNOWN;
        }
        Set<String> segments = new HashSet<>();
        for (Object segment : stored) {
            segments.add(String.valueOf(segment));
        }
        return new UserProfile(segments);
    }

    @Override
    public void close() {
        client.close();
        eventLoops.close();
    }
}

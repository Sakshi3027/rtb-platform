package com.sakshichavan.rtb.simulator;

import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.routing.ConsistentHashRing;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Chooses which bidder instance receives a request, like the load balancer in front of a bidder fleet. */
@FunctionalInterface
public interface Router {

    URI route(BidRequest request);

    static Router single(URI target) {
        return request -> target;
    }

    /** Rotates through targets with no awareness of users. A user's requests spread across every bidder. */
    static Router roundRobin(List<URI> targets) {
        List<URI> ordered = requireTargets(targets);
        AtomicLong next = new AtomicLong();
        return request -> ordered.get(Math.floorMod(next.getAndIncrement(), ordered.size()));
    }

    /**
     * Consistent hashing on the user id: every request from a user reaches the same bidder, so that bidder's
     * in-memory frequency caps see all of the user's traffic. Anonymous requests spread by request id.
     */
    static Router userAffinity(List<URI> targets) {
        List<URI> ordered = requireTargets(targets);
        Map<String, URI> byName = new HashMap<>();
        for (URI target : ordered) {
            byName.put(target.toString(), target);
        }
        ConsistentHashRing ring = new ConsistentHashRing(byName.keySet(), 200);
        return request -> {
            boolean identified = request.user() != null && request.user().id() != null;
            String key = identified ? request.user().id() : request.id();
            return byName.get(ring.nodeFor(key));
        };
    }

    static Router of(String mode, List<URI> targets) {
        return switch (mode) {
            case "round-robin" -> roundRobin(targets);
            case "user-hash" -> userAffinity(targets);
            default -> throw new IllegalArgumentException("unknown routing mode: " + mode);
        };
    }

    private static List<URI> requireTargets(List<URI> targets) {
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("at least one target is required");
        }
        return List.copyOf(targets);
    }
}

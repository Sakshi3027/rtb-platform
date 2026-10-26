package com.sakshichavan.rtb.simulator;

import com.sakshichavan.rtb.common.openrtb.BidRequest;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouterTest {

    private static final List<URI> TARGETS = List.of(
            URI.create("http://127.0.0.1:8081/bid"),
            URI.create("http://127.0.0.1:8082/bid"),
            URI.create("http://127.0.0.1:8083/bid"));

    private static final WeightedSampler<Integer> USERS = TrafficGenerator.userSampler(10_000);

    @Test
    void roundRobinCyclesThroughTargetsInOrder() {
        Router router = Router.roundRobin(TARGETS);
        BidRequest request = new TrafficGenerator(1, 0, USERS).next();

        for (int i = 0; i < 6; i++) {
            assertEquals(TARGETS.get(i % 3), router.route(request));
        }
    }

    @Test
    void userAffinitySendsEveryRequestFromAUserToTheSameTarget() {
        Router router = Router.userAffinity(TARGETS);
        TrafficGenerator generator = new TrafficGenerator(7, 0, USERS);
        Map<String, URI> firstSeen = new HashMap<>();

        for (int i = 0; i < 20_000; i++) {
            BidRequest request = generator.next();
            if (request.user() == null) {
                continue;
            }
            URI target = router.route(request);
            URI previous = firstSeen.putIfAbsent(request.user().id(), target);
            if (previous != null) {
                assertEquals(previous, target, "user " + request.user().id() + " moved between bidders");
            }
        }
    }

    @Test
    void userAffinityStillSpreadsLoadAcrossTargets() {
        Router router = Router.userAffinity(TARGETS);
        TrafficGenerator generator = new TrafficGenerator(7, 0, USERS);
        Map<URI, Integer> counts = new HashMap<>();

        for (int i = 0; i < 30_000; i++) {
            counts.merge(router.route(generator.next()), 1, Integer::sum);
        }

        for (URI target : TARGETS) {
            double share = counts.getOrDefault(target, 0) / 30_000.0;
            assertTrue(share > 0.15, target + " got only " + share);
        }
    }
}

package com.sakshichavan.rtb.common.routing;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsistentHashRingTest {

    private static final List<String> THREE_BIDDERS = List.of("bidder-a", "bidder-b", "bidder-c");
    private static final int VIRTUAL_NODES = 200;
    private static final int USERS = 100_000;

    private static String user(int i) {
        return "user-" + i;
    }

    @Test
    void sameKeyAlwaysMapsToTheSameNode() {
        ConsistentHashRing ring = new ConsistentHashRing(THREE_BIDDERS, VIRTUAL_NODES);
        ConsistentHashRing rebuilt = new ConsistentHashRing(THREE_BIDDERS, VIRTUAL_NODES);

        for (int i = 0; i < 1_000; i++) {
            assertEquals(ring.nodeFor(user(i)), ring.nodeFor(user(i)));
            assertEquals(ring.nodeFor(user(i)), rebuilt.nodeFor(user(i)));
        }
    }

    @Test
    void everyNodeGetsAFairShareOfKeys() {
        ConsistentHashRing ring = new ConsistentHashRing(THREE_BIDDERS, VIRTUAL_NODES);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < USERS; i++) {
            counts.merge(ring.nodeFor(user(i)), 1, Integer::sum);
        }

        for (String bidder : THREE_BIDDERS) {
            double share = counts.getOrDefault(bidder, 0) / (double) USERS;
            assertTrue(share > 0.25 && share < 0.42, bidder + " got share " + share);
        }
    }

    @Test
    void addingANodeMovesAboutAQuarterOfKeysAndOnlyOntoTheNewNode() {
        ConsistentHashRing before = new ConsistentHashRing(THREE_BIDDERS, VIRTUAL_NODES);
        ConsistentHashRing after = before.withNode("bidder-d");

        int moved = 0;
        for (int i = 0; i < USERS; i++) {
            String oldOwner = before.nodeFor(user(i));
            String newOwner = after.nodeFor(user(i));
            if (!oldOwner.equals(newOwner)) {
                moved++;
                assertEquals("bidder-d", newOwner, "a moved key must land on the new node");
            }
        }

        double movedShare = moved / (double) USERS;
        assertTrue(movedShare > 0.15 && movedShare < 0.35, "moved share was " + movedShare);
    }

    @Test
    void moduloHashingWouldMoveMostKeysForComparison() {
        int moved = 0;
        for (int i = 0; i < USERS; i++) {
            long hash = ConsistentHashRing.hash(user(i));
            if (Math.floorMod(hash, 3) != Math.floorMod(hash, 4)) {
                moved++;
            }
        }

        double movedShare = moved / (double) USERS;
        assertTrue(movedShare > 0.6, "modulo moved share was " + movedShare);
    }

    @Test
    void rejectsAnEmptyRing() {
        assertThrows(IllegalArgumentException.class, () -> new ConsistentHashRing(List.of(), VIRTUAL_NODES));
    }
}

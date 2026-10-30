package com.sakshichavan.rtb.bidder.budget;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class BidIdsTest {

    @Test
    void idsFromOneProcessNeverRepeat() {
        BidIds ids = BidIds.random();
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 100_000; i++) {
            seen.add(ids.next());
        }

        assertEquals(100_000, seen.size());
    }

    @Test
    void differentProcessesNeverShareIdsEvenWithIdenticalCounters() {
        BidIds first = new BidIds(0x1234L);
        BidIds second = new BidIds(0x5678L);

        assertEquals("b-1234-1", first.next());
        assertEquals("b-5678-1", second.next());
        assertNotEquals(first.next(), second.next());
    }
}

package com.sakshichavan.rtb.simulator;

import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.Imp;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrafficGeneratorTest {

    private static final WeightedSampler<Integer> USERS = TrafficGenerator.userSampler(100_000);

    private static List<BidRequest> generate(long seed, int count) {
        TrafficGenerator generator = new TrafficGenerator(seed, 0, USERS);
        List<BidRequest> requests = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            requests.add(generator.next());
        }
        return requests;
    }

    @Test
    void sameSeedProducesIdenticalTraffic() {
        assertEquals(generate(42, 1_000), generate(42, 1_000));
    }

    @Test
    void differentSeedsProduceDifferentTraffic() {
        assertNotEquals(generate(42, 100), generate(43, 100));
    }

    @Test
    void generatesValidRequests() {
        for (BidRequest request : generate(42, 10_000)) {
            assertEquals(1, request.imp().size());
            Imp imp = request.imp().get(0);
            assertTrue(imp.bidFloor() >= 0.10 && imp.bidFloor() <= 3.00, "floor " + imp.bidFloor());
            assertTrue(Set.of(80, 100, 120).contains(request.tmax()), "tmax " + request.tmax());
            assertNotNull(request.site());
            assertNotNull(request.device().geo().country());
        }
    }

    @Test
    void requestIdsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (BidRequest request : generate(42, 10_000)) {
            ids.add(request.id());
        }
        assertEquals(10_000, ids.size());
    }

    @Test
    void adSizeMixMatchesWeights() {
        List<BidRequest> requests = generate(42, 20_000);
        long mediumRectangles = requests.stream()
                .filter(r -> r.imp().get(0).banner().w() == 300 && r.imp().get(0).banner().h() == 250)
                .count();

        double share = (double) mediumRectangles / requests.size();
        assertTrue(share > 0.38 && share < 0.42, "300x250 share was " + share);
    }

    @Test
    void aboutTwentyPercentOfUsersAreAnonymous() {
        List<BidRequest> requests = generate(42, 20_000);
        long anonymous = requests.stream().filter(r -> r.user() == null).count();

        double share = (double) anonymous / requests.size();
        assertTrue(share > 0.18 && share < 0.22, "anonymous share was " + share);
    }

    @Test
    void userTrafficIsHeavilySkewed() {
        int identified = 0;
        int fromTopOnePercent = 0;
        for (BidRequest request : generate(42, 20_000)) {
            if (request.user() == null) {
                continue;
            }
            identified++;
            int rank = Integer.parseInt(request.user().id().substring("user-".length()));
            if (rank < 1_000) {
                fromTopOnePercent++;
            }
        }

        double share = (double) fromTopOnePercent / identified;
        assertTrue(share > 0.58 && share < 0.66, "top 1% user share was " + share);
    }
}

package com.sakshichavan.rtb.bidder.ml;

import com.sakshichavan.rtb.bidder.campaign.Campaign;
import com.sakshichavan.rtb.bidder.profile.UserProfile;
import com.sakshichavan.rtb.common.features.ImpressionFeatures;
import com.sakshichavan.rtb.common.openrtb.AuctionType;
import com.sakshichavan.rtb.common.openrtb.Banner;
import com.sakshichavan.rtb.common.openrtb.BidRequest;
import com.sakshichavan.rtb.common.openrtb.Imp;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CtrValuerTest {

    private static final Imp IMP = new Imp("1", new Banner(300, 250), 0.10, "USD");
    private static final BidRequest REQUEST = new BidRequest("r1", List.of(IMP), null, null, null, 100,
            AuctionType.FIRST_PRICE);
    private static final List<Campaign> CAMPAIGNS = List.of(
            new Campaign("average", "cr-a", 2.00, 10.0, null),
            new Campaign("unlikely", "cr-u", 2.00, 10.0, null),
            new Campaign("extreme", "cr-e", 2.00, 10.0, null));
    private static final Map<String, Double> CTR = Map.of("average", 0.02, "unlikely", 0.005, "extreme", 0.50);

    /** A fake model that returns a fixed probability per campaign and records each batch it receives. */
    private static final class FakeModel implements CtrModel {
        final List<Integer> batchSizes = new ArrayList<>();

        @Override
        public double[] predict(List<ImpressionFeatures> rows) {
            batchSizes.add(rows.size());
            return rows.stream().mapToDouble(row -> CTR.get(row.campaignId())).toArray();
        }
    }

    private static double[] value(CtrValuer valuer) {
        return valuer.valuesCpm(REQUEST, IMP, UserProfile.UNKNOWN, CAMPAIGNS);
    }

    @Test
    void scalesEachCampaignsPriceByItsPredictedCtrAndCapsExtremes() {
        CtrValuer valuer = new CtrValuer(new FakeModel(), 0.01, 3.0, 1_000);

        assertArrayEquals(new double[] {4.00, 1.00, 6.00}, value(valuer), 1e-9);
    }

    @Test
    void predictsAllCampaignsInOneBatchedCall() {
        FakeModel model = new FakeModel();
        CtrValuer valuer = new CtrValuer(model, 0.01, 3.0, 1_000);

        value(valuer);

        assertEquals(List.of(3), model.batchSizes);
    }

    @Test
    void repeatedFeaturesAreAnsweredFromTheCache() {
        FakeModel model = new FakeModel();
        CtrValuer valuer = new CtrValuer(model, 0.01, 3.0, 1_000);

        value(valuer);
        value(valuer);

        assertEquals(List.of(3), model.batchSizes, "the second request must not call the model");
        assertEquals(new ValuerStats(6, 3, 1, 0), valuer.stats());
    }

    @Test
    void fallsBackToFixedPricesWhenTheModelFails() {
        CtrValuer failing = new CtrValuer(rows -> {
            throw new IllegalStateException("inference failed");
        }, 0.01, 3.0, 1_000);
        CtrValuer invalid = new CtrValuer(rows -> new double[] {Double.NaN, 0.01, 0.01}, 0.01, 3.0, 1_000);

        assertArrayEquals(new double[] {2.00, 2.00, 2.00}, value(failing), 1e-9);
        assertArrayEquals(new double[] {2.00, 2.00, 2.00}, value(invalid), 1e-9);
        assertEquals(1, failing.stats().fallbacks());
        assertEquals(1, invalid.stats().fallbacks());
    }

    @Test
    void perCampaignNormalizationCentersEachCampaignOnItsOwnAverage() {
        Campaign campaign = new Campaign("average", "cr-a", 2.00, 10.0, null);
        Imp large = new Imp("1", new Banner(300, 250), 0.10, "USD");
        Imp small = new Imp("1", new Banner(728, 90), 0.10, "USD");
        CtrModel bySize = rows -> rows.stream().mapToDouble(row -> row.size().equals("300x250") ? 0.04 : 0.02).toArray();
        CtrValuer valuer = new CtrValuer(bySize, 0.01, 3.0, 1_000, CtrValuer.Normalization.PER_CAMPAIGN, 10);

        for (int i = 0; i < 50; i++) {
            valuer.valuesCpm(REQUEST, large, UserProfile.UNKNOWN, List.of(campaign));
            valuer.valuesCpm(REQUEST, small, UserProfile.UNKNOWN, List.of(campaign));
        }
        double largeValue = valuer.valuesCpm(REQUEST, large, UserProfile.UNKNOWN, List.of(campaign))[0];

        assertEquals(0.03, valuer.baselines().get("average"), 1e-4);
        assertEquals(2.00 * 0.04 / 0.03, largeValue, 1e-6, "twice the CTR of the small slot, centered on the average");
    }

    @Test
    void perCampaignNormalizationUsesTheGlobalReferenceUntilItHasEnoughSamples() {
        Campaign campaign = new Campaign("average", "cr-a", 2.00, 10.0, null);
        CtrValuer valuer = new CtrValuer(rows -> new double[] {0.02}, 0.01, 3.0, 1_000,
                CtrValuer.Normalization.PER_CAMPAIGN, 10);

        double first = valuer.valuesCpm(REQUEST, IMP, UserProfile.UNKNOWN, List.of(campaign))[0];
        for (int i = 0; i < 10; i++) {
            valuer.valuesCpm(REQUEST, IMP, UserProfile.UNKNOWN, List.of(campaign));
        }
        double settled = valuer.valuesCpm(REQUEST, IMP, UserProfile.UNKNOWN, List.of(campaign))[0];

        assertEquals(4.00, first, 1e-9, "cold start: 0.02 / global 0.01 = 2x");
        assertEquals(2.00, settled, 1e-9, "after warm-up: 0.02 / its own average 0.02 = 1x");
    }
}

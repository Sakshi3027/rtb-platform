package com.sakshichavan.rtb.bidder;

import com.sakshichavan.rtb.bidder.campaign.Campaign;
import com.sakshichavan.rtb.bidder.campaign.CampaignLoader;
import com.sakshichavan.rtb.bidder.campaign.TargetingIndex;

import java.nio.file.Path;
import java.util.List;

/** Starts the bidder. Configuration comes from environment variables (container-friendly). */
public final class BidderMain {

    private BidderMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        int port = Integer.parseInt(System.getenv().getOrDefault("BIDDER_PORT", "8080"));
        String campaignsFile = System.getenv("CAMPAIGNS_FILE");

        List<Campaign> campaigns = (campaignsFile == null)
                ? CampaignLoader.fromClasspath("/campaigns.json")
                : CampaignLoader.fromFile(Path.of(campaignsFile));
        TargetingIndex index = new TargetingIndex(campaigns);

        BidderServer server = new BidderServer(port, new TargetingBidEngine(index));
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));

        System.out.printf("bidder listening on port %d with %d campaigns%n", server.port(), index.size());
    }
}

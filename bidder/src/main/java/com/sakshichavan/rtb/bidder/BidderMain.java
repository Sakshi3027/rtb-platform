package com.sakshichavan.rtb.bidder;

import java.util.Optional;

/** Starts the bidder. Configuration comes from environment variables (container-friendly). */
public final class BidderMain {

    private BidderMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        int port = Integer.parseInt(System.getenv().getOrDefault("BIDDER_PORT", "8080"));

        BidEngine engine = request -> Optional.empty();
        BidderServer server = new BidderServer(port, engine);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));

        System.out.printf("bidder listening on port %d (engine: always no-bid)%n", server.port());
    }
}

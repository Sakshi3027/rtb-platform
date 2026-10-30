package com.sakshichavan.rtb.campaigns;

import java.io.IOException;

/** Starts the campaign service. Configuration comes from environment variables. */
public final class CampaignServiceMain {

    private CampaignServiceMain() {
    }

    private static String env(String name, String defaultValue) {
        return System.getenv().getOrDefault(name, defaultValue);
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(env("PORT", "8090"));
        String jdbcUrl = env("JDBC_URL", "jdbc:postgresql://localhost:5432/campaigns");
        CampaignStore store = new CampaignStore(jdbcUrl, env("DB_USER", "rtb"), env("DB_PASSWORD", "rtb"));
        CampaignApi api = new CampaignApi(port, store);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            api.close();
            store.close();
        }));
        System.out.printf("campaign service on port %d, database %s%n", api.port(), jdbcUrl);
    }
}

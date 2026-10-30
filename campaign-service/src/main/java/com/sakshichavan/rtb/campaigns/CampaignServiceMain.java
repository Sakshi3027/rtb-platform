package com.sakshichavan.rtb.campaigns;

import java.io.IOException;
import java.time.Duration;

/** Starts the campaign service and its outbox relay. Configuration comes from environment variables. */
public final class CampaignServiceMain {

    private CampaignServiceMain() {
    }

    private static String env(String name, String defaultValue) {
        return System.getenv().getOrDefault(name, defaultValue);
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(env("PORT", "8090"));
        String jdbcUrl = env("JDBC_URL", "jdbc:postgresql://localhost:5432/campaigns");
        String kafkaBootstrap = env("KAFKA_BOOTSTRAP", "localhost:9092");
        String topic = env("CAMPAIGNS_TOPIC", "rtb.campaigns");
        boolean relayEnabled = !"off".equals(env("RELAY", "on"));
        long relayIntervalMillis = Long.parseLong(env("RELAY_INTERVAL_MS", "200"));

        CampaignStore store = new CampaignStore(jdbcUrl, env("DB_USER", "rtb"), env("DB_PASSWORD", "rtb"));
        OutboxRelay relay = relayEnabled
                ? new OutboxRelay(store, new KafkaChangePublisher(kafkaBootstrap, topic), 500,
                        Duration.ofMillis(relayIntervalMillis))
                : null;
        CampaignApi api = new CampaignApi(port, store);
        if (relay != null) {
            relay.start();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            api.close();
            if (relay != null) {
                try {
                    relay.close();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            store.close();
        }));
        System.out.printf("campaign service on port %d, database %s, relay: %s%n", api.port(), jdbcUrl,
                relay == null ? "off" : "every " + relayIntervalMillis + "ms to " + topic + " via " + kafkaBootstrap);
    }
}

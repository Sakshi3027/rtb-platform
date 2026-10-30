package com.sakshichavan.rtb.campaigns;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sakshichavan.rtb.common.campaigns.CampaignSpec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The campaign management API (control plane). Every request runs on its own virtual thread.
 *   GET  /campaigns                     all campaigns with their versions
 *   GET  /campaigns/{id}                one campaign; its version is the ETag
 *   PUT  /campaigns/{id}                If-None-Match: * creates; If-Match: "<version>" updates (optimistic concurrency)
 *   GET  /changes?after=REV&limit=N     the ordered change log
 *   GET  /health
 */
public final class CampaignApi implements AutoCloseable {

    private static final int MAX_CHANGES_PER_PAGE = 1_000;

    private final CampaignStore store;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpServer server;

    public CampaignApi(int port, CampaignStore store) throws IOException {
        this.store = store;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/campaigns", this::handleCampaigns);
        server.createContext("/changes", this::handleChanges);
        server.createContext("/health", exchange -> respond(exchange, 200, Map.of("status", "ok"), null));
        server.createContext("/outbox", exchange -> respond(exchange, 200, store.outboxStatus(OutboxRelay.PUBLISHER), null));
        server.setExecutor(executor);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handleCampaigns(HttpExchange exchange) throws IOException {
        try {
            String rest = exchange.getRequestURI().getPath().substring("/campaigns".length());
            String method = exchange.getRequestMethod();
            if (rest.isEmpty() || rest.equals("/")) {
                if ("GET".equals(method)) {
                    respond(exchange, 200, store.list(), null);
                } else {
                    error(exchange, 405, "use GET to list campaigns");
                }
                return;
            }
            String id = rest.substring(1);
            if (id.contains("/")) {
                error(exchange, 404, "no such resource");
                return;
            }
            switch (method) {
                case "GET" -> getCampaign(exchange, id);
                case "PUT" -> putCampaign(exchange, id);
                default -> error(exchange, 405, "use GET or PUT");
            }
        } catch (RuntimeException e) {
            error(exchange, 500, "internal error");
        }
    }

    private void getCampaign(HttpExchange exchange, String id) throws IOException {
        var found = store.get(id);
        if (found.isEmpty()) {
            error(exchange, 404, "campaign not found: " + id);
        } else {
            respond(exchange, 200, found.get(), etag(found.get().version()));
        }
    }

    private void putCampaign(HttpExchange exchange, String id) throws IOException {
        CampaignSpec campaign;
        try {
            campaign = CampaignJson.read(exchange.getRequestBody().readAllBytes(), CampaignSpec.class);
        } catch (JsonProcessingException e) {
            error(exchange, 400, "invalid campaign JSON: " + e.getOriginalMessage());
            return;
        }
        if (!id.equals(campaign.id())) {
            error(exchange, 400, "the campaign id in the body must match the URL");
            return;
        }
        List<String> problems = campaign.problems();
        if (!problems.isEmpty()) {
            respond(exchange, 400, Map.of("error", "invalid campaign", "problems", problems), null);
            return;
        }

        String ifMatch = exchange.getRequestHeaders().getFirst("If-Match");
        String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
        if ("*".equals(ifNoneMatch)) {
            try {
                VersionedCampaign created = store.create(campaign);
                respond(exchange, 201, created, etag(created.version()));
            } catch (CampaignStore.AlreadyExists e) {
                error(exchange, 412, e.getMessage());
            }
        } else if (ifMatch != null) {
            long expected;
            try {
                expected = parseEtag(ifMatch);
            } catch (NumberFormatException e) {
                error(exchange, 400, "If-Match must be a version ETag such as \"3\"");
                return;
            }
            try {
                VersionedCampaign updated = store.update(campaign, expected);
                respond(exchange, 200, updated, etag(updated.version()));
            } catch (CampaignStore.NotFound e) {
                error(exchange, 404, e.getMessage());
            } catch (CampaignStore.VersionMismatch e) {
                Map<String, Object> body = Map.of(
                        "error", "the campaign was changed by someone else; re-read it and retry",
                        "currentVersion", e.currentVersion());
                respond(exchange, 412, body, etag(e.currentVersion()));
            }
        } else {
            error(exchange, 428, "send If-Match with the campaign's current ETag to update, "
                    + "or If-None-Match: * to create");
        }
    }

    private void handleChanges(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                error(exchange, 405, "use GET");
                return;
            }
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            long after;
            int limit;
            try {
                after = Long.parseLong(query.getOrDefault("after", "0"));
                limit = Math.min(MAX_CHANGES_PER_PAGE, Integer.parseInt(query.getOrDefault("limit", "100")));
            } catch (NumberFormatException e) {
                error(exchange, 400, "after and limit must be numbers");
                return;
            }
            respond(exchange, 200, store.changesAfter(after, Math.max(1, limit)), null);
        } catch (RuntimeException e) {
            error(exchange, 500, "internal error");
        }
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> values = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return values;
        }
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                values.put(pair.substring(0, equals), pair.substring(equals + 1));
            }
        }
        return values;
    }

    static String etag(long version) {
        return "\"" + version + "\"";
    }

    /** Accepts "3", W/"3", or a bare 3. */
    static long parseEtag(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("W/")) {
            trimmed = trimmed.substring(2);
        }
        return Long.parseLong(trimmed.replace("\"", ""));
    }

    private static void error(HttpExchange exchange, int status, String message) throws IOException {
        respond(exchange, status, Map.of("error", message), null);
    }

    private static void respond(HttpExchange exchange, int status, Object body, String etag) throws IOException {
        byte[] bytes = CampaignJson.writeBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (etag != null) {
            exchange.getResponseHeaders().set("ETag", etag);
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }
}

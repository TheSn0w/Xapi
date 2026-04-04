package com.xapi.debugger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP client for communicating with the RS3 map debugger server (localhost:5555).
 * Sends captured transitions and live player position updates.
 * Uses {@link HttpURLConnection} instead of {@code java.net.http.HttpClient} to avoid
 * ClosedChannelException issues when running in a child module layer.
 */
final class MapDebuggerClient {

    private static final Logger log = LoggerFactory.getLogger(MapDebuggerClient.class);
    private static final Gson GSON = new GsonBuilder().create();

    private static final String BASE_URL = "http://[::1]:5555";
    private static final int TIMEOUT_MS = 2000;

    private volatile boolean connected;
    private volatile long lastPositionSendTime;

    /** Minimum interval between position updates (ms). */
    private static final long POSITION_SEND_INTERVAL = 500;

    MapDebuggerClient() {
        log.info("MapDebuggerClient created, target: {}", BASE_URL);
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * POST a captured transition to the map debugger server.
     * @return true if the server accepted it
     */
    boolean sendTransition(XapiData.TransitionData t) {
        try {
            Map<String, Object> transition = Map.ofEntries(
                    Map.entry("type", t.type()),
                    Map.entry("srcX", t.srcX()),
                    Map.entry("srcY", t.srcY()),
                    Map.entry("srcP", t.srcP()),
                    Map.entry("dstX", t.dstX()),
                    Map.entry("dstY", t.dstY()),
                    Map.entry("dstP", t.dstP()),
                    Map.entry("name", t.name()),
                    Map.entry("option", t.option()),
                    Map.entry("cost", t.cost()),
                    Map.entry("bidir", t.bidir()),
                    Map.entry("source", "xapi_live")
            );
            String json = GSON.toJson(Map.of("transition", transition));

            int status = doPost(BASE_URL + "/api/transitions", json);
            connected = true;

            if (status == 200) {
                log.info("Transition sent: {} {} ({},{},{}) -> ({},{},{})",
                        t.type(), t.name(), t.srcX(), t.srcY(), t.srcP(),
                        t.dstX(), t.dstY(), t.dstP());
                return true;
            } else {
                log.warn("Server returned {} for transition", status);
                return false;
            }
        } catch (Exception e) {
            connected = false;
            log.debug("Failed to send transition: {}", e.getMessage());
            return false;
        }
    }

    /**
     * POST the player's current position to the map debugger for live tracking.
     * Rate-limited to avoid flooding.
     */
    void sendPlayerPosition(int x, int y, int plane) {
        long now = System.currentTimeMillis();
        if (now - lastPositionSendTime < POSITION_SEND_INTERVAL) return;
        lastPositionSendTime = now;

        try {
            String json = GSON.toJson(Map.of("x", x, "y", y, "plane", plane));
            int status = doPost(BASE_URL + "/api/player-position", json);
            connected = (status == 200);
        } catch (Exception e) {
            connected = false;
            log.debug("sendPlayerPosition failed: {}", e.getMessage());
        }
    }

    /**
     * Check if a transition already exists on the server.
     * @return true if it exists (duplicate), false if new or on error
     */
    boolean checkDuplicate(XapiData.TransitionData t) {
        try {
            String query = String.format("?srcX=%d&srcY=%d&srcP=%d&dstX=%d&dstY=%d&dstP=%d&name=%s",
                    t.srcX(), t.srcY(), t.srcP(), t.dstX(), t.dstY(), t.dstP(),
                    URLEncoder.encode(t.name(), StandardCharsets.UTF_8));

            String body = doGet(BASE_URL + "/api/transitions/check" + query);
            connected = true;
            return body != null && body.contains("true");
        } catch (Exception e) {
            connected = false;
            return false;
        }
    }

    boolean isConnected() {
        return connected;
    }

    /**
     * Lightweight connectivity check — GET /api/player-position.
     * Updates the {@link #connected} flag without sending any data.
     */
    void ping() {
        try {
            String body = doGet(BASE_URL + "/api/player-position");
            connected = (body != null);
            log.info("Ping result: connected={}", connected);
        } catch (Exception e) {
            connected = false;
            log.debug("Ping failed: {}", e.getMessage());
        }
    }

    // ── HTTP helpers (HttpURLConnection) ─────────────────────────────────

    private int doPost(String url, String jsonBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            return conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
    }

    private String doGet(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);

            int status = conn.getResponseCode();
            if (status == 200) {
                return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }
            return null;
        } finally {
            conn.disconnect();
        }
    }
}

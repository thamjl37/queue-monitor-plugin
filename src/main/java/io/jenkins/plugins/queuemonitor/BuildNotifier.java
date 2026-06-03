package io.jenkins.plugins.queuemonitor;

import hudson.util.Secret;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends build-completion notifications to a configurable HTTP endpoint.
 * Called from BuildPickupListener.onFinalized for every completed build.
 */
public final class BuildNotifier {

    private static final Logger LOG = Logger.getLogger(BuildNotifier.class.getName());
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS    = 30_000;

    private BuildNotifier() {}

    public static void send(String jobName, int buildNumber, String jobUrl,
                            String startTime, String endTime, String status,
                            String log, List<SlaveUsageDetail> agents) {
        GlobalConfig cfg = GlobalConfig.get();
        if (cfg == null || !cfg.isNotificationEnabled()) return;

        String endpoint = cfg.getNotificationEndpointUrl();
        if (endpoint == null || endpoint.isBlank()) return;

        String json = buildJson(jobName, buildNumber, jobUrl, startTime, endTime, status, log, agents);
        try {
            int httpStatus = doPost(endpoint, json, cfg);
            if (httpStatus < 200 || httpStatus >= 300) {
                LOG.warning(String.format("[QueueMonitor] Notification endpoint returned HTTP %d for build %s #%d",
                    httpStatus, jobName, buildNumber));
            } else {
                LOG.fine(String.format("[QueueMonitor] Build notification sent (HTTP %d): %s #%d",
                    httpStatus, jobName, buildNumber));
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING,
                String.format("[QueueMonitor] Failed to send build notification for %s #%d: %s",
                    jobName, buildNumber, e.getMessage()), e);
        }
    }

    private static int doPost(String endpointUrl, String json, GlobalConfig cfg) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpointUrl).openConnection();
        try {
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            applyAuth(conn, cfg);

            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            return conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
    }

    private static void applyAuth(HttpURLConnection conn, GlobalConfig cfg) {
        String username = cfg.getNotificationUsername();
        String password = Secret.toString(cfg.getNotificationPasswordSecret());
        String bearer   = Secret.toString(cfg.getNotificationBearerTokenSecret());

        if (username != null && !username.isBlank()) {
            // Basic auth takes priority when a username is present
            String encoded = Base64.getEncoder().encodeToString(
                (username + ":" + (password != null ? password : ""))
                    .getBytes(StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + encoded);
        } else if (bearer != null && !bearer.isBlank()) {
            conn.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        // else: no auth header
    }

    private static String buildJson(String jobName, int buildNumber, String jobUrl,
                                    String startTime, String endTime, String status,
                                    String log, List<SlaveUsageDetail> agents) {
        JSONObject obj = new JSONObject();
        obj.put("jobName",     jobName     != null ? jobName     : "");
        obj.put("buildNumber", buildNumber);
        obj.put("jobUrl",      jobUrl      != null ? jobUrl      : "");
        obj.put("startTime",   startTime   != null ? startTime   : "");
        obj.put("endTime",     endTime     != null ? endTime     : "");
        obj.put("status",      status      != null ? status      : "UNKNOWN");
        obj.put("log",         log         != null ? log         : "");

        JSONArray agentArray = new JSONArray();
        if (agents != null) {
            for (SlaveUsageDetail s : agents) {
                JSONObject ag = new JSONObject();
                ag.put("slaveName", s.slaveName != null ? s.slaveName : "");
                ag.put("label",     s.label     != null ? s.label     : "");
                ag.put("usedFrom",  s.usedFrom  != null ? s.usedFrom.toString()  : "");
                ag.put("usedUntil", s.usedUntil != null ? s.usedUntil.toString() : "");
                agentArray.add(ag);
            }
        }
        obj.put("agents", agentArray);
        return obj.toString();
    }
}

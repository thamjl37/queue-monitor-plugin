package io.jenkins.plugins.queuemonitor;

import hudson.Extension;
import hudson.model.RootAction;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Exposes the plugin dashboard at /queue-monitor and provides REST endpoints
 * for JSON data access. Stapler maps doXxx() methods to URL segment "xxx":
 *
 *   GET /queue-monitor/apiSnapshot  → doApiSnapshot
 *   GET /queue-monitor/apiHistory   → doApiHistory
 *   GET /queue-monitor/apiPickups   → doApiPickups   (full list, newest-first)
 *   GET /queue-monitor/apiScaling   → doApiScaling   (full list, newest-first)
 *
 * Pagination and filtering for pickups/scaling are done client-side in
 * dashboard.js so the server endpoints stay as simple as possible and are
 * not sensitive to edge-case subList / stream behaviour under Stapler's
 * classloader. The in-memory ring-buffer is already bounded (maxSnapshots),
 * so payload size is always predictable.
 */
@Extension
public class QueueMonitorAction implements RootAction {

    private static final Logger LOG = Logger.getLogger(QueueMonitorAction.class.getName());

    @Override public String getIconFileName()  { return "graph.png"; }
    @Override public String getDisplayName()   { return "Queue Monitor"; }
    @Override public String getUrlName()       { return "queue-monitor"; }

    // -----------------------------------------------------------------------
    // REST endpoints (Stapler routing)
    // -----------------------------------------------------------------------

    /** GET /queue-monitor/apiSnapshot → current state */
    public void doApiSnapshot(StaplerRequest req, StaplerResponse rsp) throws IOException {
        Jenkins.get().checkPermission(Jenkins.READ);
        MetricsStore store = MetricsStore.get();
        QueueSnapshot snap = store != null ? store.getLatestSnapshot() : null;

        JSONObject out = new JSONObject();
        if (snap == null) {
            out.put("error", "No snapshot collected yet");
        } else {
            out.put("timestamp",          snap.timestamp.toString());
            out.put("totalQueueDepth",    snap.totalQueueDepth);
            out.put("totalExecutors",     snap.totalExecutors);
            out.put("busyExecutors",      snap.busyExecutors);
            out.put("utilizationPercent", snap.executorUtilizationPercent());
            out.put("queueByLabel",       toJsonObject(snap.queueDepthByLabel));
            out.put("busyByLabel",        toJsonObject(snap.busyByLabel));
            out.put("totalByLabel",       toJsonObject(snap.totalByLabel));

            JSONObject saturation = new JSONObject();
            for (String label : snap.totalByLabel.keySet()) {
                saturation.put(label, snap.isLabelSaturated(label));
            }
            out.put("saturationByLabel", saturation);
        }
        sendJson(rsp, out);
    }

    /** GET /queue-monitor/apiHistory?limit=N → recent N snapshots for chart */
    public void doApiHistory(StaplerRequest req, StaplerResponse rsp) throws IOException {
        Jenkins.get().checkPermission(Jenkins.READ);
        int limit = parseIntParam(req.getParameter("limit"), 60);
        MetricsStore store = MetricsStore.get();
        List<QueueSnapshot> snaps = store != null ? store.getSnapshots() : Collections.<QueueSnapshot>emptyList();

        int from = Math.max(0, snaps.size() - limit);
        JSONArray arr = new JSONArray();
        for (int i = from; i < snaps.size(); i++) {
            QueueSnapshot s = snaps.get(i);
            JSONObject o = new JSONObject();
            o.put("ts",         s.timestamp.toString());
            o.put("queueDepth", s.totalQueueDepth);
            o.put("busy",       s.busyExecutors);
            o.put("total",      s.totalExecutors);
            arr.add(o);
        }
        sendJson(rsp, arr);
    }

    /**
     * GET /queue-monitor/apiPickups → all pickup events, newest-first.
     *
     * Returns a plain JSONArray. Pagination and filtering are handled
     * client-side in dashboard.js.
     */
    public void doApiPickups(StaplerRequest req, StaplerResponse rsp) throws IOException {
        try {
            Jenkins.get().checkPermission(Jenkins.READ);
            MetricsStore store = MetricsStore.get();
            List<PickupEvent> events = store != null
                ? store.getPickupEvents()
                : Collections.<PickupEvent>emptyList();

            JSONArray arr = new JSONArray();
            // Iterate newest-first
            for (int i = events.size() - 1; i >= 0; i--) {
                PickupEvent e = events.get(i);
                JSONObject o = new JSONObject();
                o.put("ts",          e.timestamp.toString());
                o.put("job",         e.jobName);
                o.put("agent",       e.agentName);
                o.put("label",       e.matchedLabel);
                o.put("queueWaitMs", e.queueWaitMs);
                arr.add(o);
            }
            sendJson(rsp, arr);
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            LOG.warning("[QueueMonitor] doApiPickups error: " + ex);
            sendJson(rsp, new JSONArray());
        }
    }

    /**
     * GET /queue-monitor/apiScaling → all scaling audit events, newest-first.
     *
     * Returns a plain JSONArray. Pagination and filtering are handled
     * client-side in dashboard.js.
     */
    public void doApiScaling(StaplerRequest req, StaplerResponse rsp) throws IOException {
        try {
            Jenkins.get().checkPermission(Jenkins.READ);
            MetricsStore store = MetricsStore.get();
            List<ScalingEvent> events = store != null
                ? store.getScalingEvents()
                : Collections.<ScalingEvent>emptyList();

            JSONArray arr = new JSONArray();
            for (int i = events.size() - 1; i >= 0; i--) {
                ScalingEvent e = events.get(i);
                JSONObject o = new JSONObject();
                o.put("ts",        e.timestamp.toString());
                o.put("agent",     e.agentName);
                o.put("direction", e.newExecutors > e.previousExecutors ? "Scale Up" : "Scale Down");
                o.put("from",      e.previousExecutors);
                o.put("to",        e.newExecutors);
                o.put("reason",    e.reason);
                o.put("freeCpu",   e.freeCpuPercent);
                o.put("freeMem",   e.freeMemoryMb);
                arr.add(o);
            }
            sendJson(rsp, arr);
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            LOG.warning("[QueueMonitor] doApiScaling error: " + ex);
            sendJson(rsp, new JSONArray());
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private JSONObject toJsonObject(Map<String, Integer> map) {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            o.put(entry.getKey(), entry.getValue());
        }
        return o;
    }

    private void sendJson(StaplerResponse rsp, Object payload) throws IOException {
        rsp.setContentType("application/json;charset=UTF-8");
        rsp.setHeader("Cache-Control", "no-cache");
        rsp.getWriter().write(payload.toString());
    }

    private int parseIntParam(String val, int def) {
        if (val == null) return def;
        try { return Math.min(Math.max(1, Integer.parseInt(val)), 10000); }
        catch (NumberFormatException e) { return def; }
    }
}

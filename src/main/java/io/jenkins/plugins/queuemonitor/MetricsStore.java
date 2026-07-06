package io.jenkins.plugins.queuemonitor;

import hudson.Extension;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * In-memory ring-buffer store for all collected metrics.
 *
 * Uses ConcurrentLinkedDeque so reads never block the background poller.
 * Old entries are evicted on every write, so memory is bounded by config.
 */
@Extension
public class MetricsStore extends GlobalConfiguration {

    private static final Logger LOG = Logger.getLogger(MetricsStore.class.getName());

    private final Deque<QueueSnapshot> snapshots   = new ConcurrentLinkedDeque<>();
    private final Deque<PickupEvent>   pickups      = new ConcurrentLinkedDeque<>();
    private final Deque<ScalingEvent>  scalingAudit = new ConcurrentLinkedDeque<>();

    public static MetricsStore get() {
        return GlobalConfiguration.all().get(MetricsStore.class);
    }

    // -----------------------------------------------------------------------
    // Snapshots
    // -----------------------------------------------------------------------

    public synchronized void addSnapshot(QueueSnapshot s) {
        snapshots.addLast(s);
        evict(snapshots);
    }

    public List<QueueSnapshot> getSnapshots() {
        return new ArrayList<>(snapshots);
    }

    public QueueSnapshot getLatestSnapshot() {
        return snapshots.isEmpty() ? null : snapshots.peekLast();
    }

    // -----------------------------------------------------------------------
    // Pickup events
    // -----------------------------------------------------------------------

    public synchronized void addPickupEvent(PickupEvent e) {
        pickups.addLast(e);
        evict(pickups);
        // Persistence is deferred to onCompleted so the correct label is written.
        // See BuildPickupListener.onCompleted → persistLatestPickup(jobName).
    }

    /**
     * Finds the most recent pickup event for the given job and writes it to the
     * JSONL file. Called from BuildPickupListener.onCompleted after the label
     * has been retroactively corrected so the file always records the final label.
     */
    public void persistLatestPickup(String jobName) {
        List<PickupEvent> list = new ArrayList<>(pickups);
        for (int i = list.size() - 1; i >= 0; i--) {
            PickupEvent e = list.get(i);
            if (e.jobName.equals(jobName)) {
                persistPickupEvent(e);
                return;
            }
        }
    }

    /**
     * Appends the pickup event as a JSON line to
     * $JENKINS_HOME/queue-monitor-pickups.jsonl.
     * Each line is a self-contained JSON object so the file can be
     * tailed, grepped, or imported into any log analysis tool.
     */
    private void persistPickupEvent(PickupEvent e) {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) return;
        File out = new File(jenkins.getRootDir(), "queue-monitor-pickups.jsonl");
        String line = String.format(
            "{\"ts\":\"%s\",\"job\":\"%s\",\"agent\":\"%s\",\"label\":\"%s\",\"queueWaitMs\":%d}",
            e.timestamp.toString(),
            escape(e.jobName),
            escape(e.agentName),
            escape(e.matchedLabel),
            e.queueWaitMs);
        try (BufferedWriter w = new BufferedWriter(new FileWriter(out, true))) {
            w.write(line);
            w.newLine();
        } catch (IOException ex) {
            LOG.log(Level.WARNING, "[QueueMonitor] Failed to write pickup event to file", ex);
        }
    }

    /** Minimal JSON string escaping for embedded quotes and backslashes. */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public List<PickupEvent> getPickupEvents() {
        return new ArrayList<>(pickups);
    }

    /**
     * Retroactively updates the label on the most recent pickup event for a job
     * where the label is still the fallback "built-in" value.
     * Called from BuildPickupListener.onCompleted once the real label is known.
     */
    public void updatePickupLabel(String jobName, String resolvedLabel) {
        // Iterate newest-first; update the first matching unresolved event
        List<PickupEvent> list = new ArrayList<>(pickups);
        for (int i = list.size() - 1; i >= 0; i--) {
            PickupEvent e = list.get(i);
            if (e.jobName.equals(jobName) && "built-in".equals(e.matchedLabel)) {
                e.matchedLabel = resolvedLabel;
                LOG.info(String.format(
                    "[QueueMonitor] Pickup label updated: job='%s' → '%s'",
                    jobName, resolvedLabel));
                return;
            }
        }
    }

    // -----------------------------------------------------------------------
    // Scaling audit
    // -----------------------------------------------------------------------

    public synchronized void addScalingEvent(ScalingEvent e) {
        scalingAudit.addLast(e);
        evict(scalingAudit);
    }

    public List<ScalingEvent> getScalingEvents() {
        return new ArrayList<>(scalingAudit);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void evict(Deque<?> deque) {
        GlobalConfig cfg = GlobalConfig.get();
        int maxItems = cfg != null ? cfg.getMaxSnapshots() : 2880;
        while (deque.size() > maxItems) deque.pollFirst();
    }

    @Override
    public String getDisplayName() {
        return "Queue Monitor Metrics Store";
    }
}

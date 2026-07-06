package io.jenkins.plugins.queuemonitor;

import hudson.Extension;
import jenkins.model.Jenkins;

import java.time.Instant;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Detects a sustained increasing trend in total queue depth and triggers an
 * email alert (via {@link QueueTrendNotifier}) when one is found.
 *
 * A "sustained increasing trend" means the last
 * {@link GlobalConfig#getTrendSustainedSamples()} snapshots each strictly exceed
 * the one before them, and the latest snapshot is at or above
 * {@link GlobalConfig#getTrendMinQueueDepth()} (filters out noise near zero).
 *
 * Once an alert fires, {@link GlobalConfig#getTrendNotificationCooldownSeconds()}
 * must elapse before another can be sent, and the trend must still be increasing
 * at that time — a cooldown expiring during a lull does not trigger a repeat.
 *
 * Called once per poll cycle from {@link QueueMetricsCollector#execute}, right
 * after a fresh snapshot is stored.
 */
@Extension
public class QueueTrendMonitor {

    private static final Logger LOG = Logger.getLogger(QueueTrendMonitor.class.getName());

    /** Epoch seconds of the last successfully sent alert (0 = never sent). */
    private volatile long lastNotifiedAt = 0;

    public static QueueTrendMonitor get() {
        return Jenkins.get().getExtensionList(QueueTrendMonitor.class).get(0);
    }

    public void evaluate() {
        GlobalConfig cfg = GlobalConfig.get();
        if (cfg == null || !cfg.isTrendNotificationEnabled()) return;

        MetricsStore store = MetricsStore.get();
        if (store == null) return;

        int sustainedSamples = cfg.getTrendSustainedSamples();
        List<QueueSnapshot> snapshots = store.getSnapshots();
        if (snapshots.size() < sustainedSamples + 1) return; // not enough history yet

        List<QueueSnapshot> tail = snapshots.subList(snapshots.size() - (sustainedSamples + 1), snapshots.size());
        for (int i = 1; i < tail.size(); i++) {
            if (tail.get(i).totalQueueDepth <= tail.get(i - 1).totalQueueDepth) {
                return; // not a sustained increase
            }
        }

        int fromDepth = tail.get(0).totalQueueDepth;
        int toDepth = tail.get(tail.size() - 1).totalQueueDepth;
        if (toDepth < cfg.getTrendMinQueueDepth()) return;

        long now = Instant.now().getEpochSecond();
        if (lastNotifiedAt != 0 && (now - lastNotifiedAt) < cfg.getTrendNotificationCooldownSeconds()) {
            LOG.fine("[QueueMonitor] Trend alert suppressed: cooldown active");
            return;
        }

        try {
            if (QueueTrendNotifier.send(cfg, fromDepth, toDepth, sustainedSamples)) {
                lastNotifiedAt = now;
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[QueueMonitor] Trend notifier error", e);
        }
    }
}

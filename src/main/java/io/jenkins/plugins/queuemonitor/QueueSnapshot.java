package io.jenkins.plugins.queuemonitor;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/** Immutable point-in-time snapshot of queue and executor state. */
public final class QueueSnapshot {

    public final Instant timestamp;

    /** Total items waiting in the build queue. */
    public final int totalQueueDepth;

    /** Queue depth broken down by label. */
    public final Map<String, Integer> queueDepthByLabel;

    /** Total executors across all online agents. */
    public final int totalExecutors;

    /** Currently busy executors. */
    public final int busyExecutors;

    /** Busy executor count per label. */
    public final Map<String, Integer> busyByLabel;

    /** Total (online) executors per label. */
    public final Map<String, Integer> totalByLabel;

    public QueueSnapshot(
            Instant timestamp,
            int totalQueueDepth,
            Map<String, Integer> queueDepthByLabel,
            int totalExecutors,
            int busyExecutors,
            Map<String, Integer> busyByLabel,
            Map<String, Integer> totalByLabel) {
        this.timestamp = timestamp;
        this.totalQueueDepth = totalQueueDepth;
        this.queueDepthByLabel = Collections.unmodifiableMap(queueDepthByLabel);
        this.totalExecutors = totalExecutors;
        this.busyExecutors = busyExecutors;
        this.busyByLabel = Collections.unmodifiableMap(busyByLabel);
        this.totalByLabel = Collections.unmodifiableMap(totalByLabel);
    }

    /** True when every executor for this label is occupied. */
    public boolean isLabelSaturated(String label) {
        int total = totalByLabel.getOrDefault(label, 0);
        if (total == 0) return false;
        return busyByLabel.getOrDefault(label, 0) >= total;
    }

    public double executorUtilizationPercent() {
        if (totalExecutors == 0) return 0.0;
        return 100.0 * busyExecutors / totalExecutors;
    }
}

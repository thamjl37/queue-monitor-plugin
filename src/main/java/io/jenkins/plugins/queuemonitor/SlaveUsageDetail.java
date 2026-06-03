package io.jenkins.plugins.queuemonitor;

import java.time.Instant;

/** Records the period an agent/slave was in use for one build. */
public final class SlaveUsageDetail {

    public final String slaveName;
    public final String label;
    public final Instant usedFrom;
    public final Instant usedUntil;

    public SlaveUsageDetail(String slaveName, String label, Instant usedFrom, Instant usedUntil) {
        this.slaveName = slaveName;
        this.label     = label;
        this.usedFrom  = usedFrom;
        this.usedUntil = usedUntil;
    }
}

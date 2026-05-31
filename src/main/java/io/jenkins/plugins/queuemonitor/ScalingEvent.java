package io.jenkins.plugins.queuemonitor;

import java.time.Instant;

/** Audit record for every automatic executor scaling decision. */
public final class ScalingEvent {

    public final Instant timestamp;
    public final String agentName;
    public final int previousExecutors;
    public final int newExecutors;
    public final String reason;
    public final double freeCpuPercent;
    public final long freeMemoryMb;

    public ScalingEvent(Instant timestamp, String agentName,
                        int previousExecutors, int newExecutors,
                        String reason, double freeCpuPercent, long freeMemoryMb) {
        this.timestamp         = timestamp;
        this.agentName         = agentName;
        this.previousExecutors = previousExecutors;
        this.newExecutors      = newExecutors;
        this.reason            = reason;
        this.freeCpuPercent    = freeCpuPercent;
        this.freeMemoryMb      = freeMemoryMb;
    }
}

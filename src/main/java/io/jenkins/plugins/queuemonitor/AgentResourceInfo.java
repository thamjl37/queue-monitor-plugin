package io.jenkins.plugins.queuemonitor;

/** Lightweight snapshot of an agent's current resource state. */
public final class AgentResourceInfo {

    public final String nodeName;
    public final int    currentExecutors;
    public final int    busyExecutors;
    public final double freeCpuPercent;
    public final long   freeMemoryMb;

    public AgentResourceInfo(String nodeName, int currentExecutors, int busyExecutors,
                             double freeCpuPercent, long freeMemoryMb) {
        this.nodeName         = nodeName;
        this.currentExecutors = currentExecutors;
        this.busyExecutors    = busyExecutors;
        this.freeCpuPercent   = freeCpuPercent;
        this.freeMemoryMb     = freeMemoryMb;
    }

    public int availableExecutors() {
        return Math.max(0, currentExecutors - busyExecutors);
    }

    public boolean meetsScalingThresholds(GlobalConfig cfg) {
        return freeCpuPercent    >= cfg.getScalingMinFreeCpuPercent()
            && freeMemoryMb      >= cfg.getScalingMinFreeMemoryMb()
            && currentExecutors  <  cfg.getMaxExecutorsPerAgent();
    }
}

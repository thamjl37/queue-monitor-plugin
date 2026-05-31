package io.jenkins.plugins.queuemonitor;

import java.time.Instant;

/** Records a single job execution pickup by an agent. */
public final class PickupEvent {

    public final Instant timestamp;
    public final String  jobName;
    public final String  agentName;
    public volatile String matchedLabel; // volatile: may be updated by onCompleted thread
    public final long    queueWaitMs;

    public PickupEvent(Instant timestamp, String jobName, String agentName,
                       String matchedLabel, long queueWaitMs) {
        this.timestamp    = timestamp;
        this.jobName      = jobName;
        this.agentName    = agentName;
        this.matchedLabel = matchedLabel;
        this.queueWaitMs  = queueWaitMs;
    }
}

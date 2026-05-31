package io.jenkins.plugins.queuemonitor;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import jenkins.model.Jenkins;

import java.time.Instant;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Listens for build starts to record execution pickup events.
 *
 * Also records build duration on completion for anomaly baseline building.
 */
@Extension
public class BuildPickupListener extends RunListener<Run<?, ?>> {

    private static final Logger LOG = Logger.getLogger(BuildPickupListener.class.getName());

    @Override
    public void onStarted(Run<?, ?> run, TaskListener listener) {
        try {
            recordPickup(run);
        } catch (Exception e) {
            LOG.fine("[QueueMonitor] Could not record pickup: " + e.getMessage());
        }
    }

    @Override
    public void onCompleted(Run<?, ?> run, TaskListener listener) {
        try {
            long duration = run.getDuration();
            if (duration > 0) {
                MetricsStore store = MetricsStore.get();
                if (store != null) store.recordBuildDuration(run.getParent().getFullName(), duration);
                checkDurationAnomaly(run, duration);
            }
        } catch (Exception e) {
            LOG.fine("[QueueMonitor] Could not record duration: " + e.getMessage());
        }

        // For WorkflowJob (pipeline) runs, the pickup label may have been recorded as
        // "built-in" because sub-tasks didn't exist yet when onStarted fired.
        // Now that the build is complete, all node() steps have run — refresh hints
        // from running executors and update any unresolved pickup entries.
        try {
            String jobName = run.getParent().getFullName();
            QueueMetricsCollector collector = QueueMetricsCollector.get();
            MetricsStore store = MetricsStore.get();
            if (collector != null && store != null) {
                liveRefreshHints(collector);
                String hint = collector.getJobLabelHint(jobName);
                if (hint != null && !"built-in".equals(hint)) {
                    store.updatePickupLabel(jobName, hint);
                }
                // Write the pickup event to the JSONL file now that the label is final.
                store.persistLatestPickup(jobName);
            }
        } catch (Exception e) {
            LOG.fine("[QueueMonitor] Could not update pickup label: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------

    private void recordPickup(Run<?, ?> run) {
        Executor executor = run.getExecutor();
        if (executor == null) return;

        Computer computer = executor.getOwner(); // getOwner() is @Nonnull per Executor API
        Node node = computer.getNode();
        String agentName = node != null ? node.getNodeName() : "built-in";
        if (agentName.isEmpty()) agentName = "built-in";

        // Determine matched label: find intersection of job label and agent labels
        String matchedLabel = resolveMatchedLabel(run, node);

        // Queue wait = time from when the run was created (queued) to when it actually started.
        // run.getTimeInMillis() is the scheduled/queued time for all Run types.
        // run.getStartTimeInMillis() is when the executor actually began running it.
        long queueWaitMs = Math.max(0, run.getStartTimeInMillis() - run.getTimeInMillis());

        PickupEvent event = new PickupEvent(
            Instant.now(),
            run.getParent().getFullName(),
            agentName,
            matchedLabel,
            Math.max(0, queueWaitMs)
        );

        MetricsStore store = MetricsStore.get();
        if (store != null) store.addPickupEvent(event);

        LOG.fine(String.format("[QueueMonitor] Pickup: job='%s' agent='%s' label='%s' waitMs=%d",
            event.jobName, agentName, matchedLabel, queueWaitMs));
    }

    private String resolveMatchedLabel(Run<?, ?> run, Node node) {
        String jobName = run.getParent().getFullName();

        try {
            QueueMetricsCollector collector = QueueMetricsCollector.get();

            // Priority 1: label hint derived from pipeline sub-tasks.
            // If the pre-populated hint is missing (e.g. first run after restart,
            // or the parent job started before the 30s collector tick), do an
            // immediate live scan of the queue AND running executors right now.
            if (collector != null) {
                if (collector.getJobLabelHint(jobName) == null) {
                    liveRefreshHints(collector);
                }
                String hint = collector.getJobLabelHint(jobName);
                if (hint != null) return hint;
            }

            // Priority 2: label expression configured directly on the job (AbstractProject).
            if (run.getParent() instanceof AbstractProject) {
                Label required = ((AbstractProject<?, ?>) run.getParent()).getAssignedLabel();
                if (required != null) return required.getName();
            }

            // Priority 3: Queue.Task.getAssignedLabel() — but only if it is not the
            // node's own self-label (which pipelines always return for the built-in node).
            if (run.getParent() instanceof hudson.model.Queue.Task) {
                try {
                    Label required = ((hudson.model.Queue.Task) run.getParent()).getAssignedLabel();
                    if (required != null) {
                        String selfName = node != null
                            ? (node.getNodeName().isEmpty() ? "built-in" : node.getNodeName())
                            : "";
                        if (!required.getName().equals(selfName)) return required.getName();
                    }
                } catch (Exception ignored) {}
            }

        } catch (Exception e) {
            LOG.fine("[QueueMonitor] resolveMatchedLabel error: " + e.getMessage());
        }

        // Final fallback: node display name
        if (node == null) return "unknown";
        String name = node.getNodeName();
        return name.isEmpty() ? "built-in" : name;
    }

    private static final Pattern PART_OF_PATTERN =
        Pattern.compile("^part of (.+) #\\d+$");

    /** Returns true if the label is a node self-label (e.g. "built-in") — not a real job constraint. */
    private static boolean isNodeSelfLabel(String labelName, Jenkins jenkins) {
        if ("built-in".equals(labelName)) return true;
        for (Node node : jenkins.getNodes()) {
            if (node.getNodeName().equals(labelName)) return true;
        }
        return false;
    }

    /**
     * Scans the current queue AND all running executors for sub-task display names
     * matching "part of <jobName> #<N>" and pushes the findings into the collector's
     * hint map immediately — without waiting for the next 30s poll cycle.
     */
    private void liveRefreshHints(QueueMetricsCollector collector) {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) return;
        try {
            // Scan queued items
            for (hudson.model.Queue.Item item : jenkins.getQueue().getItems()) {
                Label lbl = null;
                try { lbl = item.task.getAssignedLabel(); } catch (Exception ignored) {}
                if (lbl == null || isNodeSelfLabel(lbl.getName(), jenkins)) continue;
                Matcher m = PART_OF_PATTERN.matcher(item.task.getDisplayName());
                if (m.matches()) {
                    collector.putJobLabelHint(m.group(1), lbl.getName());
                    LOG.info(String.format("[QueueMonitor] liveHint(queue): '%s' → '%s'",
                        m.group(1), lbl.getName()));
                }
            }
            // Scan currently running executors — sub-tasks that already left the queue
            for (Computer computer : jenkins.getComputers()) {
                for (Executor executor : computer.getExecutors()) {
                    if (executor.isIdle()) continue;
                    try {
                        hudson.model.Queue.Executable exec = executor.getCurrentExecutable();
                        if (exec == null) continue;
                        Label lbl = exec.getParent().getAssignedLabel();
                        if (lbl == null || isNodeSelfLabel(lbl.getName(), jenkins)) continue;
                        Matcher m = PART_OF_PATTERN.matcher(exec.getParent().getDisplayName());
                        if (m.matches()) {
                            collector.putJobLabelHint(m.group(1), lbl.getName());
                            LOG.info(String.format("[QueueMonitor] liveHint(executor): '%s' → '%s'",
                                m.group(1), lbl.getName()));
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            LOG.fine("[QueueMonitor] liveRefreshHints error: " + e.getMessage());
        }
    }

    private void checkDurationAnomaly(Run<?, ?> run, long durationMs) {
        MetricsStore store = MetricsStore.get();
        GlobalConfig cfg   = GlobalConfig.get();
        if (store == null || cfg == null) return;

        String jobName = run.getParent().getFullName();
        double baseline = store.getBaselineAvgMs(jobName);
        if (baseline <= 0) return;

        double factor = (double) durationMs / baseline;
        if (factor >= cfg.getBuildDurationAnomalyFactor()) {
            LOG.warning(String.format(
                "[QueueMonitor] ALERT: Build '%s #%d' took %.1f× baseline (%.1fs vs avg %.1fs). Possible Nexus delay.",
                jobName, run.getNumber(),
                factor, durationMs / 1000.0, baseline / 1000.0));
        }
    }
}

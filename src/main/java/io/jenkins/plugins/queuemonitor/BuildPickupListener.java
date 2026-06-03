package io.jenkins.plugins.queuemonitor;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Listens for build lifecycle events to:
 *   - Record execution pickup events (agent, label, queue wait time)
 *   - Detect build-duration anomalies against a per-job baseline
 *   - POST a JSON payload to the configured webhook endpoint after every build
 *
 * Agent usage timing for pipeline builds is provided by AgentUsageTracker
 * (GraphListener), which fires precise events at each node() block start/end.
 * No log parsing is used for labels or timings.
 */
@Extension
public class BuildPickupListener extends RunListener<Run<?, ?>> {

    private static final Logger LOG = Logger.getLogger(BuildPickupListener.class.getName());

    /**
     * Tracks the primary executor's agent and start time for each in-progress build.
     * Used as a fallback for non-pipeline (freestyle) builds that have no GraphListener data.
     * Key = run.getExternalizableId()
     */
    private final Map<String, AgentStart> inProgressAgents = new ConcurrentHashMap<>();

    private static final class AgentStart {
        final String slaveName;
        final String label;
        final Instant usedFrom;
        AgentStart(String slaveName, String label, Instant usedFrom) {
            this.slaveName = slaveName;
            this.label     = label;
            this.usedFrom  = usedFrom;
        }
    }

    // -----------------------------------------------------------------------
    // RunListener hooks
    // -----------------------------------------------------------------------

    @Override
    public void onStarted(Run<?, ?> run, TaskListener listener) {
        try {
            recordPickup(run);
        } catch (Exception e) {
            LOG.fine("[QueueMonitor] Could not record pickup: " + e.getMessage());
        }
        try {
            trackAgentStart(run);
        } catch (Exception e) {
            LOG.fine("[QueueMonitor] Could not track agent start: " + e.getMessage());
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

        // Refresh pipeline label hints now that all node() steps have completed
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
                store.persistLatestPickup(jobName);
            }
        } catch (Exception e) {
            LOG.fine("[QueueMonitor] Could not update pickup label: " + e.getMessage());
        }
    }

    @Override
    public void onFinalized(Run<?, ?> run) {
        try {
            sendBuildNotification(run);
        } catch (Exception e) {
            LOG.fine("[QueueMonitor] Could not send build notification: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Pickup recording
    // -----------------------------------------------------------------------

    private void recordPickup(Run<?, ?> run) {
        Executor executor = run.getExecutor();
        if (executor == null) return;

        Computer computer = executor.getOwner();
        Node node = computer.getNode();
        String agentName = node != null ? node.getNodeName() : "built-in";
        if (agentName.isEmpty()) agentName = "built-in";

        String matchedLabel = resolveMatchedLabel(run, node);

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

    private void trackAgentStart(Run<?, ?> run) {
        Executor executor = run.getExecutor();
        if (executor == null) return;
        Computer computer = executor.getOwner();
        Node node = computer.getNode();
        String agentName = (node != null && !node.getNodeName().isEmpty())
            ? node.getNodeName() : "built-in";
        String label = resolveMatchedLabel(run, node);
        inProgressAgents.put(run.getExternalizableId(),
            new AgentStart(agentName, label, Instant.ofEpochMilli(run.getStartTimeInMillis())));
    }

    // -----------------------------------------------------------------------
    // Build notification
    // -----------------------------------------------------------------------

    private void sendBuildNotification(Run<?, ?> run) {
        GlobalConfig cfg = GlobalConfig.get();
        if (cfg == null || !cfg.isNotificationEnabled()) return;

        AgentStart primary = inProgressAgents.remove(run.getExternalizableId());

        long startMs    = run.getStartTimeInMillis();
        long durationMs = run.getDuration();
        Instant buildStart = Instant.ofEpochMilli(startMs);
        Instant buildEnd   = durationMs > 0
            ? Instant.ofEpochMilli(startMs + durationMs)
            : Instant.now();

        // Pipeline builds: per-block agent usage with precise timing from AgentUsageTracker
        // (populated by GraphListener events, no log parsing)
        List<SlaveUsageDetail> agents = Collections.emptyList();
        AgentUsageTracker tracker = AgentUsageTracker.get();
        if (tracker != null) {
            agents = tracker.getAndRemoveUsage(run.getExternalizableId());
        }

        // Freestyle / non-pipeline fallback: single entry from onStarted
        if (agents.isEmpty() && primary != null) {
            agents = Collections.singletonList(
                new SlaveUsageDetail(primary.slaveName, primary.label, primary.usedFrom, buildEnd));
        }

        String log = readLog(run, cfg.getNotificationMaxLogLines());

        Jenkins jenkins = Jenkins.getInstanceOrNull();
        String rootUrl  = jenkins != null && jenkins.getRootUrl() != null ? jenkins.getRootUrl() : "";

        Result result = run.getResult();
        String status = result != null ? result.toString() : "UNKNOWN";

        BuildNotifier.send(
            run.getParent().getFullName(),
            run.getNumber(),
            rootUrl + run.getUrl(),
            buildStart.toString(),
            buildEnd.toString(),
            status,
            log,
            agents
        );
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private String resolveMatchedLabel(Run<?, ?> run, Node node) {
        String jobName = run.getParent().getFullName();
        try {
            QueueMetricsCollector collector = QueueMetricsCollector.get();
            if (collector != null) {
                if (collector.getJobLabelHint(jobName) == null) {
                    liveRefreshHints(collector);
                }
                String hint = collector.getJobLabelHint(jobName);
                if (hint != null) return hint;
            }

            if (run.getParent() instanceof AbstractProject) {
                Label required = ((AbstractProject<?, ?>) run.getParent()).getAssignedLabel();
                if (required != null) return required.getName();
            }

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

        if (node == null) return "unknown";
        String name = node.getNodeName();
        return name.isEmpty() ? "built-in" : name;
    }

    private static final Pattern PART_OF_PATTERN =
        Pattern.compile("^part of (.+) #\\d+$");

    private static boolean isNodeSelfLabel(String labelName, Jenkins jenkins) {
        if ("built-in".equals(labelName)) return true;
        for (Node node : jenkins.getNodes()) {
            if (node.getNodeName().equals(labelName)) return true;
        }
        return false;
    }

    private void liveRefreshHints(QueueMetricsCollector collector) {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) return;
        try {
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

    private static String readLog(Run<?, ?> run, int maxLines) {
        try {
            int limit = maxLines > 0 ? maxLines : Integer.MAX_VALUE;
            List<String> lines = run.getLog(limit);
            return String.join("\n", lines);
        } catch (IOException e) {
            LOG.fine("[QueueMonitor] Could not read build log: " + e.getMessage());
            return "";
        }
    }
}

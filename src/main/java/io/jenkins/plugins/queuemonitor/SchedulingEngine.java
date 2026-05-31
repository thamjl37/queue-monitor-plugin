package io.jenkins.plugins.queuemonitor;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Slave;
import jenkins.model.Jenkins;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Evaluates queued jobs and, when enabled by GlobalConfig, relieves queue pressure
 * in this priority order:
 *
 *   1. Scale up executors on an already-compatible agent (preferred — keeps label integrity).
 *   2. Recommend dynamic label assignment to a capable agent (fallback — only when scaling
 *      is disabled or no compatible agent can be scaled within thresholds).
 *
 * Executor scaling is always attempted first because it preserves the existing label
 * topology and avoids routing jobs to agents that may not have the right environment.
 * Dynamic label assignment is a last resort and is surfaced as a recommendation only.
 *
 * All decisions are logged and recorded in MetricsStore for audit.
 */
@Extension
public class SchedulingEngine {

    private static final Logger LOG = Logger.getLogger(SchedulingEngine.class.getName());

    /** Tracks the last scale event time (up or down) per agent name (epoch seconds). */
    private final Map<String, Long> lastScaledAt = new ConcurrentHashMap<>();

    public static SchedulingEngine get() {
        return Jenkins.get().getExtensionList(SchedulingEngine.class).get(0);
    }

    // -----------------------------------------------------------------------
    // Main entry-point called by the periodic collector
    // -----------------------------------------------------------------------

    public void evaluate() {
        GlobalConfig cfg = GlobalConfig.get();
        if (cfg == null) return;

        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) return;

        if (!cfg.isDynamicLabelEnabled() && !cfg.isExecutorScalingEnabled()) return;

        hudson.model.Queue queue = jenkins.getQueue();
        hudson.model.Queue.Item[] items = queue.getItems();

        List<AgentResourceInfo> agents = collectAgentInfo(jenkins);

        // ── Scale-down when queue is empty ────────────────────────────────────
        // If there is nothing waiting AND executor scaling is enabled, step down
        // any agent that has more executors than the configured minimum floor.
        // One step per evaluation cycle so descent is gradual and logged.
        if (items.length == 0) {
            if (cfg.isExecutorScalingEnabled()) {
                tryExecutorScaleDown(agents, jenkins, cfg);
            }
            return;
        }

        // ── Scale-up path (queue has waiting items) ───────────────────────────
        LOG.info(String.format("[QueueMonitor] evaluate(): %d queued items, %d agents tracked",
            items.length, agents.size()));
        agents.forEach(a -> LOG.info(String.format(
            "[QueueMonitor]   agent='%s' total=%d busy=%d freeCPU=%.1f%% freeMem=%dMB",
            a.nodeName, a.currentExecutors, a.busyExecutors, a.freeCpuPercent, a.freeMemoryMb)));

        // Deduplicate: once we decide to scale for any saturated label, stop iterating.
        boolean scaledThisCycle = false;

        for (hudson.model.Queue.Item item : items) {
            if (scaledThisCycle) break;

            // Resolve the label constraint for this queue item.
            // Works for AbstractProject (freestyle, matrix) and any SubTask that
            // exposes an assigned label via its task.
            Label requiredLabel = null;
            if (item.task instanceof AbstractProject) {
                requiredLabel = ((AbstractProject<?, ?>) item.task).getAssignedLabel();
            } else {
                // Pipeline, matrix sub-builds, etc. — try to get label via SubTask API
                try {
                    hudson.model.Queue.Task task = item.task;
                    requiredLabel = task.getAssignedLabel();
                } catch (Exception ignored) {}
            }

            // Find agents that are already compatible with this item's label
            List<AgentResourceInfo> compatible = compatibleAgents(agents, requiredLabel, jenkins);

            // If a compatible agent already has a free executor, nothing to do for this item
            boolean hasAvailable = compatible.stream().anyMatch(a -> a.availableExecutors() > 0);
            if (hasAvailable) continue;

            LOG.info(String.format(
                "[QueueMonitor] Item '%s' waiting (label='%s'): no free executors on %d compatible agent(s)",
                item.task.getFullDisplayName(),
                requiredLabel != null ? requiredLabel.getName() : "any",
                compatible.size()));

            // Step 1: try scaling executors on a compatible agent (preferred)
            if (cfg.isExecutorScalingEnabled()) {
                scaledThisCycle = tryExecutorScaling(compatible, agents, requiredLabel, jenkins, cfg);
            }

            // Step 2: only if scaling was not possible, recommend dynamic label assignment
            if (!scaledThisCycle && cfg.isDynamicLabelEnabled() && requiredLabel != null) {
                tryDynamicLabelAssignment(
                    item.task.getFullDisplayName(), requiredLabel, agents, jenkins);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Executor scale-down (queue empty → return agents to minimum baseline)
    // -----------------------------------------------------------------------

    /**
     * For every online agent whose current executor count exceeds
     * {@code minExecutorsPerAgent}, reduces executors by 1 (one step per
     * evaluation cycle so the descent is gradual).  Skips agents that are
     * still busy (any running executors) or on cooldown to avoid interrupting
     * active work or thrashing immediately after a scale-up.
     */
    private void tryExecutorScaleDown(
            List<AgentResourceInfo> agents, Jenkins jenkins, GlobalConfig cfg) {

        int floor = cfg.getMinExecutorsPerAgent();

        for (AgentResourceInfo info : agents) {
            // Never reduce below the floor
            if (info.currentExecutors <= floor) continue;

            // Don't touch agents that are still running jobs
            if (info.busyExecutors > 0) {
                LOG.fine(String.format(
                    "[QueueMonitor] Scale-down skip '%s': %d executor(s) still busy",
                    info.nodeName, info.busyExecutors));
                continue;
            }

            // Respect the shared cooldown so we don't scale-down immediately
            // after a scale-up (or another scale-down) on the same agent.
            if (isOnCooldown(info.nodeName, cfg)) {
                LOG.fine(String.format(
                    "[QueueMonitor] Scale-down skip '%s': on cooldown", info.nodeName));
                continue;
            }

            boolean isBuiltIn = "built-in".equals(info.nodeName);
            Node node = isBuiltIn ? jenkins : jenkins.getNode(info.nodeName);
            if (node == null) continue;

            int current;
            if (node instanceof Slave) {
                current = ((Slave) node).getNumExecutors();
            } else if (node instanceof Jenkins) {
                current = ((Jenkins) node).getNumExecutors();
            } else {
                continue;
            }

            int proposed = Math.max(current - 1, floor);
            if (proposed >= current) continue;

            try {
                if (node instanceof Slave) {
                    ((Slave) node).setNumExecutors(proposed);
                } else {
                    ((Jenkins) node).setNumExecutors(proposed);
                }
                lastScaledAt.put(info.nodeName, Instant.now().getEpochSecond());

                ScalingEvent event = new ScalingEvent(
                    Instant.now(), info.nodeName, current, proposed,
                    "scale-down: queue empty",
                    info.freeCpuPercent, info.freeMemoryMb);

                MetricsStore store = MetricsStore.get();
                if (store != null) store.addScalingEvent(event);

                LOG.info(String.format(
                    "[QueueMonitor] Scaled DOWN executors on '%s': %d → %d (queue empty, floor=%d)",
                    info.nodeName, current, proposed, floor));

                QueueMetricsCollector collector = QueueMetricsCollector.get();
                if (collector != null) collector.refreshNow();

            } catch (Exception e) {
                LOG.log(Level.WARNING,
                    "[QueueMonitor] Failed to scale-down executors on " + info.nodeName, e);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Dynamic label assignment (fallback — only reached when executor scaling
    // is disabled or no compatible agent met the scaling thresholds)
    // -----------------------------------------------------------------------

    private void tryDynamicLabelAssignment(
            String jobDisplayName, Label requiredLabel,
            List<AgentResourceInfo> allAgents, Jenkins jenkins) {

        for (AgentResourceInfo info : allAgents) {
            if (info.availableExecutors() == 0) continue;

            Node node = "built-in".equals(info.nodeName) ? jenkins : jenkins.getNode(info.nodeName);
            if (node == null) continue;
            // Skip agents that already carry the required label
            if (node.getAssignedLabels().stream()
                    .anyMatch(l -> l.getName().equals(requiredLabel.getName()))) continue;

            LOG.info(String.format(
                "[QueueMonitor] Dynamic label fallback: recommend assigning label '%s' to agent '%s'"
                + " for job '%s' — executor scaling was not possible (review required)",
                requiredLabel.getName(), info.nodeName, jobDisplayName));
            break;
        }
    }

    // -----------------------------------------------------------------------
    // Resource-aware executor scaling
    // -----------------------------------------------------------------------

    /**
     * Attempts to scale up executors on one compatible agent.
     * Prefers agents that already carry the required label (compatible list).
     * Falls back to all agents only when the compatible list is empty.
     *
     * @return true if a scale-up was performed, false if no eligible agent was found
     */
    private boolean tryExecutorScaling(
            List<AgentResourceInfo> compatible,
            List<AgentResourceInfo> allAgents,
            Label requiredLabel,
            Jenkins jenkins, GlobalConfig cfg) {

        // Always prefer label-compatible agents; fall back to all agents only if none exist
        List<AgentResourceInfo> candidates = compatible.isEmpty() ? allAgents : compatible;

        for (AgentResourceInfo info : candidates) {
            if (isOnCooldown(info.nodeName, cfg)) {
                LOG.info(String.format("[QueueMonitor] Scaling skip '%s': on cooldown", info.nodeName));
                continue;
            }
            if (!info.meetsScalingThresholds(cfg)) {
                LOG.info(String.format(
                    "[QueueMonitor] Scaling skip '%s': thresholds not met "
                    + "(freeCPU=%.1f%% req=%d%%, freeMem=%dMB req=%dMB, executors=%d max=%d)",
                    info.nodeName,
                    info.freeCpuPercent, cfg.getScalingMinFreeCpuPercent(),
                    info.freeMemoryMb,   cfg.getScalingMinFreeMemoryMb(),
                    info.currentExecutors, cfg.getMaxExecutorsPerAgent()));
                continue;
            }

            // Resolve the node — "built-in" is the display name we assigned; the real
            // built-in controller has an empty node name, so getNode("") returns null.
            // In that case, the Jenkins instance itself IS the node.
            boolean isBuiltIn = "built-in".equals(info.nodeName);
            Node node = isBuiltIn ? jenkins : jenkins.getNode(info.nodeName);
            if (node == null) continue;

            // Both Slave (remote agents) and Jenkins (built-in controller) support
            // setNumExecutors, but they are separate classes — handle each explicitly.
            int current;
            if (node instanceof Slave) {
                current = ((Slave) node).getNumExecutors();
            } else if (node instanceof Jenkins) {
                current = ((Jenkins) node).getNumExecutors();
            } else {
                continue; // unsupported node type
            }

            int proposed = Math.min(current + 1, cfg.getMaxExecutorsPerAgent());
            if (proposed <= current) continue;

            try {
                if (node instanceof Slave) {
                    ((Slave) node).setNumExecutors(proposed);
                } else {
                    ((Jenkins) node).setNumExecutors(proposed);
                }
                lastScaledAt.put(info.nodeName, Instant.now().getEpochSecond());

                ScalingEvent event = new ScalingEvent(
                    Instant.now(), info.nodeName, current, proposed,
                    requiredLabel != null
                        ? "label saturation: " + requiredLabel.getName()
                        : "general queue pressure",
                    info.freeCpuPercent, info.freeMemoryMb);

                MetricsStore store = MetricsStore.get();
                if (store != null) store.addScalingEvent(event);

                LOG.info(String.format(
                    "[QueueMonitor] Scaled executors on '%s': %d → %d (label='%s', CPU free: %.1f%%, Mem free: %d MB)",
                    info.nodeName, current, proposed,
                    requiredLabel != null ? requiredLabel.getName() : "any",
                    info.freeCpuPercent, info.freeMemoryMb));

                // Immediately refresh the snapshot so the dashboard reflects
                // the new executor count without waiting for the next poll cycle.
                QueueMetricsCollector collector = QueueMetricsCollector.get();
                if (collector != null) collector.refreshNow();

                return true; // one scale-up per evaluation cycle is enough
            } catch (Exception e) {
                LOG.log(Level.WARNING, "[QueueMonitor] Failed to scale executors on " + info.nodeName, e);
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private List<AgentResourceInfo> collectAgentInfo(Jenkins jenkins) {
        List<AgentResourceInfo> result = new ArrayList<>();
        for (Computer computer : jenkins.getComputers()) {
            if (computer.isOffline()) continue;
            Node node = computer.getNode();
            if (node == null) continue;

            List<Executor> executors = computer.getExecutors();
            int total = executors.size();
            int busy  = (int) executors.stream().filter(e -> !e.isIdle()).count();

            double freeCpu = getFreeCpuPercent(computer);
            long   freeMem = getFreeMemoryMb(computer);

            // The built-in controller has an empty node name; use "built-in" for display
            String name = node.getNodeName().isEmpty() ? "built-in" : node.getNodeName();
            result.add(new AgentResourceInfo(name, total, busy, freeCpu, freeMem));
        }
        return result;
    }

    private List<AgentResourceInfo> compatibleAgents(
            List<AgentResourceInfo> agents, Label requiredLabel, Jenkins jenkins) {
        if (requiredLabel == null) return new ArrayList<>(agents);
        List<AgentResourceInfo> result = new ArrayList<>();
        for (AgentResourceInfo info : agents) {
            Node node = "built-in".equals(info.nodeName) ? jenkins : jenkins.getNode(info.nodeName);
            if (node == null) continue;
            if (requiredLabel.matches(node)) result.add(info);
        }
        return result;
    }

    /**
     * Returns true if fewer than {@code scalingCooldownSeconds} have elapsed
     * since the last scale event (up or down) on this agent.
     * The cooldown is a hard floor — it is always honoured regardless of queue depth.
     */
    private boolean isOnCooldown(String nodeName, GlobalConfig cfg) {
        Long last = lastScaledAt.get(nodeName);
        if (last == null) return false;
        return (Instant.now().getEpochSecond() - last) < cfg.getScalingCooldownSeconds();
    }

    /**
     * Reads system CPU load via the JVM MXBean.
     * Uses getCpuLoad() (JDK 14+) with fallback to the deprecated getSystemCpuLoad()
     * for older runtimes. Returns free CPU percentage (0-100).
     * Falls back to 100% free if the reading is unavailable, so scaling is NOT blocked
     * by a missing CPU reading.
     */
    private double getFreeCpuPercent(Computer computer) {
        try {
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOs =
                    (com.sun.management.OperatingSystemMXBean) os;
                // getCpuLoad() is the JDK 14+ replacement for getSystemCpuLoad()
                double load = -1;
                try {
                    load = sunOs.getCpuLoad();          // JDK 14+, JRE 21
                } catch (NoSuchMethodError e) {
                    load = sunOs.getSystemCpuLoad();    // pre-JDK 14 fallback
                }
                if (load >= 0) {
                    double freePct = Math.max(0.0, (1.0 - load) * 100.0);
                    LOG.fine(String.format("[QueueMonitor] System CPU load=%.1f%%, free=%.1f%%",
                        load * 100, freePct));
                    return freePct;
                }
            }
        } catch (Exception ignored) {}
        // Reading unavailable — return 100% free so CPU is never the blocker
        return 100.0;
    }

    /**
     * Returns free physical system RAM in MB using the OS MXBean.
     * Uses getFreeMemorySize() (JDK 14+) with fallback to getFreePhysicalMemorySize().
     * Falls back to a large value (8 GB) if the reading is unavailable so that
     * memory is never the sole blocker when the OS cannot be queried.
     *
     * This intentionally reads OS-level RAM, NOT JVM heap, because heap headroom
     * shrinks under load even when the host machine has plenty of physical memory.
     */
    private long getFreeMemoryMb(Computer computer) {
        try {
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOs =
                    (com.sun.management.OperatingSystemMXBean) os;
                long freeBytes = -1;
                try {
                    freeBytes = sunOs.getFreeMemorySize();        // JDK 14+
                } catch (NoSuchMethodError e) {
                    freeBytes = sunOs.getFreePhysicalMemorySize(); // pre-JDK 14 fallback
                }
                if (freeBytes > 0) {
                    long freeMb = freeBytes / (1024 * 1024);
                    LOG.fine(String.format("[QueueMonitor] OS free RAM: %d MB", freeMb));
                    return freeMb;
                }
            }
        } catch (Exception ignored) {}
        return 8192L; // reading unavailable — assume 8 GB free so memory never blocks scaling
    }
}

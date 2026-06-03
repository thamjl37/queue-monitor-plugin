package io.jenkins.plugins.queuemonitor;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Run;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.actions.ArgumentsAction;
import org.jenkinsci.plugins.workflow.flow.GraphListener;
import org.jenkinsci.plugins.workflow.graph.BlockEndNode;
import org.jenkinsci.plugins.workflow.graph.BlockStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.actions.WorkspaceAction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Tracks per-node()-block agent usage for pipeline builds using the
 * GraphListener extension point.
 *
 * - BlockStartNode fires the moment the block body begins executing on an agent.
 *   We record Instant.now() as usedFrom.
 * - BlockEndNode fires when the body finishes and the agent is released.
 *   We record Instant.now() as usedUntil, then build a SlaveUsageDetail.
 *
 * WorkspaceAction (added by ExecutorStep only) distinguishes node() blocks from
 * stage(), parallel(), withEnv() etc., which do not allocate an agent.
 *
 * No log parsing is used anywhere in this class.
 */
@Extension
public class AgentUsageTracker implements GraphListener.Synchronous {

    private static final Logger LOG = Logger.getLogger(AgentUsageTracker.class.getName());

    /**
     * Tracks when each node() block started.
     * Key: runId + "::" + blockStartNodeId (unique per block within the entire Jenkins instance).
     */
    private final Map<String, Instant> blockStartTimes = new ConcurrentHashMap<>();

    /**
     * Accumulates completed agent-usage entries keyed by runId.
     * Built up as BlockEndNode events arrive; consumed by BuildPickupListener.onFinalized.
     */
    private final Map<String, List<SlaveUsageDetail>> completedByRun = new ConcurrentHashMap<>();

    public static AgentUsageTracker get() {
        Jenkins j = Jenkins.getInstanceOrNull();
        return j != null ? j.getExtensionList(AgentUsageTracker.class).get(0) : null;
    }

    // -----------------------------------------------------------------------
    // GraphListener.Synchronous
    // -----------------------------------------------------------------------

    @Override
    public void onNewHead(FlowNode node) {
        try {
            if (node instanceof BlockStartNode) {
                onBlockStart((BlockStartNode) node);
            } else if (node instanceof BlockEndNode) {
                onBlockEnd((BlockEndNode) node);
            }
        } catch (Exception e) {
            LOG.fine("[QueueMonitor] AgentUsageTracker error: " + e.getMessage());
        }
    }

    private void onBlockStart(BlockStartNode node) {
        String runId = toRunId(node);
        if (runId == null) return;
        // Record start time for every block; validated at end time via WorkspaceAction
        blockStartTimes.put(runId + "::" + node.getId(), Instant.now());
    }

    private void onBlockEnd(BlockEndNode node) {
        BlockStartNode startNode = node.getStartNode();

        // WorkspaceAction is added exclusively by ExecutorStep (the node() step).
        // Its presence is the definitive signal that this block ran on an agent.
        // stage(), parallel(), withEnv(), etc. do NOT have a WorkspaceAction.
        WorkspaceAction ws = startNode.getAction(WorkspaceAction.class);
        if (ws == null) return;

        String runId = toRunId(node);
        if (runId == null) return;

        String blockKey = runId + "::" + startNode.getId();
        Instant startTime = blockStartTimes.remove(blockKey);
        if (startTime == null) startTime = Instant.now(); // defensive; should not happen

        // Agent name set by the executor at allocation time — not from log
        String agentName = ws.getNode();
        if (agentName == null || agentName.isEmpty()) agentName = "built-in";

        // Label: the argument passed to node('label'), not from log
        String label = resolveLabel(startNode, agentName);

        SlaveUsageDetail detail = new SlaveUsageDetail(agentName, label, startTime, Instant.now());
        completedByRun
            .computeIfAbsent(runId, k -> Collections.synchronizedList(new ArrayList<>()))
            .add(detail);

        LOG.fine(String.format(
            "[QueueMonitor] node() block complete: run=%s agent=%s label=%s from=%s until=%s",
            runId, agentName, label, detail.usedFrom, detail.usedUntil));
    }

    // -----------------------------------------------------------------------
    // Called by BuildPickupListener.onFinalized
    // -----------------------------------------------------------------------

    /**
     * Returns the completed agent-usage list for a run and removes it from the map.
     * Also purges any dangling block-start entries for builds that ended abnormally.
     */
    public List<SlaveUsageDetail> getAndRemoveUsage(String runId) {
        List<SlaveUsageDetail> usage = completedByRun.remove(runId);
        blockStartTimes.keySet().removeIf(k -> k.startsWith(runId + "::"));
        return usage != null ? new ArrayList<>(usage) : Collections.emptyList();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Resolves the label for a node() block.
     *   1. ArgumentsAction on the BlockStartNode — the actual label argument passed
     *      to node('label'), populated by the pipeline engine from the Groovy AST.
     *   2. Configured labels of the agent looked up from Jenkins node config.
     *   3. Agent name as last resort.
     */
    private static String resolveLabel(BlockStartNode startNode, String agentName) {
        ArgumentsAction args = startNode.getAction(ArgumentsAction.class);
        if (args != null) {
            Object lbl = args.getArguments().get("label");
            if (lbl instanceof String && !((String) lbl).isBlank()) {
                return (String) lbl;
            }
        }
        return lookupAgentLabels(agentName);
    }

    /**
     * Returns the configured label string of the Jenkins node whose log name matches
     * agentName. The built-in controller appears in logs as the Jenkins display name
     * (e.g. "Jenkins") but has an empty node name internally.
     */
    private static String lookupAgentLabels(String agentName) {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) return agentName;
        for (Computer c : jenkins.getComputers()) {
            Node node = c.getNode();
            if (node == null) continue;
            String nodeName = node.getNodeName();
            boolean isBuiltIn = nodeName.isEmpty();
            boolean matches = nodeName.equals(agentName)
                || (isBuiltIn && (agentName.equals("built-in")
                    || agentName.equals(jenkins.getDisplayName())));
            if (matches) {
                String configured = node.getLabelString();
                return (configured != null && !configured.isBlank())
                    ? configured.trim() : agentName;
            }
        }
        return agentName;
    }

    private static String toRunId(FlowNode node) {
        try {
            Queue.Executable exec = node.getExecution().getOwner().getExecutable();
            if (exec instanceof Run) return ((Run<?, ?>) exec).getExternalizableId();
        } catch (Exception ignored) {}
        return null;
    }
}

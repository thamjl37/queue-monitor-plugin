package io.jenkins.plugins.queuemonitor;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background collector that samples queue and executor state periodically.
 *
 * Extends AsyncPeriodicWork so Jenkins manages the thread lifecycle.
 * The recurrence period is read from GlobalConfig on every tick, so changes
 * to the poll interval take effect at the next wake-up without a restart.
 *
 * Label Status is keyed by the compound label expressions that jobs actually
 * use (e.g. "main&&windows"), not by individual node label atoms.
 * This keeps the dashboard relevant — labels nobody requests are not shown.
 */
@Extension
public class QueueMetricsCollector extends hudson.model.AsyncPeriodicWork {

    private static final Logger LOG = Logger.getLogger(QueueMetricsCollector.class.getName());

    /**
     * Remembers every compound label expression seen across all cycles so that
     * Label Status remains populated even when the queue is temporarily empty.
     * Also used by BuildPickupListener to resolve the best label for jobs that
     * have no explicit label constraint themselves.
     */
    private final Set<String> knownJobLabels = new LinkedHashSet<>();

    /**
     * Maps a parent job name to the label expression used by its sub-tasks.
     * Populated by parsing sub-task display names like "part of testing_job #21".
     * Lets BuildPickupListener resolve the correct label for WorkflowJob runs.
     */
    private final Map<String, String> jobLabelHints = new ConcurrentHashMap<>();

    /** Pattern matching pipeline sub-task display names: "part of <jobName> #<N>" */
    private static final Pattern PART_OF_PATTERN =
        Pattern.compile("^part of (.+) #\\d+$");

    /** Returns a snapshot of all compound label expressions seen so far. */
    public Set<String> getKnownJobLabels() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(knownJobLabels));
    }

    /**
     * Returns the label expression last seen for sub-tasks belonging to the given
     * parent job name, or null if no sub-tasks have been observed yet.
     */
    public String getJobLabelHint(String jobName) {
        return jobLabelHints.get(jobName);
    }

    /** Stores a job→label hint and persists it to disk immediately. */
    public void putJobLabelHint(String jobName, String labelName) {
        String prev = jobLabelHints.put(jobName, labelName);
        // Only write to disk when the value actually changed to avoid constant I/O
        if (!labelName.equals(prev)) {
            saveHints();
        }
    }

    /** Returns all current job→label hint mappings (for debug logging). */
    public Map<String, String> getAllJobLabelHints() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(jobLabelHints));
    }

    /** File name used to persist job→label hints across Jenkins restarts. */
    private static final String HINTS_FILE = "queue-monitor-hints.properties";

    public QueueMetricsCollector() {
        super("QueueMetricsCollector");
        loadHints(); // restore hints from previous run so first builds get correct labels
    }

    // -----------------------------------------------------------------------
    // Hint persistence (survives Jenkins restarts)
    // -----------------------------------------------------------------------

    /**
     * Writes the current job→label hints map to
     * {@code $JENKINS_HOME/queue-monitor-hints.properties}.
     * Called whenever a new hint is discovered so the file is always up-to-date.
     */
    private void saveHints() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) return;
        File f = new File(jenkins.getRootDir(), HINTS_FILE);
        Properties props = new Properties();
        props.putAll(jobLabelHints);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            props.store(fos, "Queue Monitor job→label hints (auto-generated, do not edit)");
        } catch (IOException e) {
            LOG.fine("[QueueMonitor] Could not save hints: " + e.getMessage());
        }
    }

    /**
     * Loads previously persisted hints into {@code jobLabelHints} on startup.
     * Any hint loaded here will be used immediately for the first builds after
     * a Jenkins restart, eliminating the "built-in" label for known jobs.
     */
    private void loadHints() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) return;
        File f = new File(jenkins.getRootDir(), HINTS_FILE);
        if (!f.exists()) return;
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(f)) {
            props.load(fis);
            for (String key : props.stringPropertyNames()) {
                jobLabelHints.put(key, props.getProperty(key));
            }
            LOG.info(String.format("[QueueMonitor] Loaded %d job→label hint(s) from disk", props.size()));
        } catch (IOException e) {
            LOG.fine("[QueueMonitor] Could not load hints: " + e.getMessage());
        }
    }

    @Override
    public long getRecurrencePeriod() {
        GlobalConfig cfg = GlobalConfig.get();
        long seconds = cfg != null ? cfg.getPollIntervalSeconds() : 30;
        return seconds * 1000L;
    }

    @Override
    protected void execute(TaskListener listener) {
        try {
            collectAndStore();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[QueueMonitor] Collection error", e);
        }
        try {
            SchedulingEngine engine = SchedulingEngine.get();
            if (engine != null) engine.evaluate();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[QueueMonitor] Scheduling engine error", e);
        }
    }

    public static QueueMetricsCollector get() {
        return Jenkins.get().getExtensionList(QueueMetricsCollector.class).get(0);
    }

    /** Called after a scaling event to push a fresh snapshot immediately. */
    public void refreshNow() {
        try {
            collectAndStore();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "[QueueMonitor] Refresh error", e);
        }
    }

    private void collectAndStore() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) return;

        hudson.model.Queue queue = jenkins.getQueue();
        hudson.model.Queue.Item[] items = queue.getItems();

        // ── Queue depth keyed by the compound label expression jobs request ──
        Map<String, Integer> queueByLabel = new LinkedHashMap<>();
        for (hudson.model.Queue.Item item : items) {
            Label label = resolveItemLabel(item);
            if (label != null) {
                String name = label.getName();
                queueByLabel.merge(name, 1, Integer::sum);

                // Only add to knownJobLabels if this is a sub-task
                // (display name matches "part of <jobName> #<N>").
                // Top-level pipeline jobs in the queue return their node's
                // self-label (e.g. "built-in") which is not a meaningful constraint.
                Matcher m = PART_OF_PATTERN.matcher(item.task.getDisplayName());
                if (m.matches() && !isNodeSelfLabel(name, jenkins)) {
                    knownJobLabels.add(name);
                    putJobLabelHint(m.group(1), name);
                }
            }
        }

        // ── Collect online computers once ──
        List<Computer> online = new ArrayList<>();
        int totalExec = 0;
        int busyExec  = 0;
        for (Computer c : jenkins.getComputers()) {
            if (c.isOffline()) continue;
            online.add(c);
            List<Executor> execs = c.getExecutors();
            totalExec += execs.size();
            busyExec  += (int) execs.stream().filter(e -> !e.isIdle()).count();
        }

        // ── Scan running executors for sub-task labels ──
        // Sub-tasks (e.g. pipeline node() blocks) may have already left the queue
        // and be running. Scanning executors ensures their labels appear in
        // knownJobLabels even if the 30s collector never caught them in the queue.
        for (Computer c : online) {
            for (Executor e : c.getExecutors()) {
                if (e.isIdle()) continue;
                try {
                    hudson.model.Queue.Executable exec = e.getCurrentExecutable();
                    if (exec == null) continue;
                    Label execLabel = exec.getParent().getAssignedLabel();
                    if (execLabel == null) continue;
                    String execLabelName = execLabel.getName();
                    Matcher m = PART_OF_PATTERN.matcher(exec.getParent().getDisplayName());
                    if (m.matches() && !isNodeSelfLabel(execLabelName, jenkins)) {
                        // Sub-task: add its compound label to knownJobLabels and hints
                        knownJobLabels.add(execLabelName);
                        putJobLabelHint(m.group(1), execLabelName);
                    }
                } catch (Exception ignored) {}
            }
        }

        // ── Executor counts keyed by known compound job labels ──
        // For each label expression, sum executors on every node that satisfies it.
        Map<String, Integer> totalByLabel = new LinkedHashMap<>();
        Map<String, Integer> busyByLabel  = new LinkedHashMap<>();

        for (String labelName : knownJobLabels) {
            Label label = jenkins.getLabel(labelName);
            if (label == null) continue;
            for (Computer c : online) {
                Node node = c.getNode();
                if (node == null) continue;
                if (!label.matches(node)) continue;
                List<Executor> execs = c.getExecutors();
                totalByLabel.merge(labelName, execs.size(), Integer::sum);
                busyByLabel.merge(labelName,
                    (int) execs.stream().filter(e -> !e.isIdle()).count(),
                    Integer::sum);
            }
        }

        // Ensure every online agent appears in the table even when no jobs carry
        // a label constraint (e.g. bare node{} pipelines). Self-labels are
        // intentionally excluded from knownJobLabels, so we add them here as
        // a fallback keyed by node name so the table is never completely empty.
        for (Computer c : online) {
            Node node = c.getNode();
            if (node == null) continue;
            String name = node.getNodeName().isEmpty() ? "built-in" : node.getNodeName();
            if (!totalByLabel.containsKey(name)) {
                List<Executor> execs = c.getExecutors();
                totalByLabel.put(name, execs.size());
                busyByLabel.put(name,
                    (int) execs.stream().filter(e -> !e.isIdle()).count());
            }
        }

        QueueSnapshot snap = new QueueSnapshot(
            Instant.now(),
            items.length,
            queueByLabel,
            totalExec,
            busyExec,
            busyByLabel,
            totalByLabel
        );

        MetricsStore store = MetricsStore.get();
        if (store != null) store.addSnapshot(snap);
    }

    /** Resolves the label expression a queued item requires, for any task type. */
    private Label resolveItemLabel(hudson.model.Queue.Item item) {
        if (item.task instanceof AbstractProject) {
            return ((AbstractProject<?, ?>) item.task).getAssignedLabel();
        }
        try { return item.task.getAssignedLabel(); } catch (Exception ignored) {}
        return null;
    }

    /**
     * Returns true if the label name is a node's own self-label (e.g. "built-in",
     * or a node named "agent1" having self-label "agent1"). Self-labels are not
     * meaningful job constraints and must not appear in Label Status.
     */
    private boolean isNodeSelfLabel(String labelName, Jenkins jenkins) {
        if ("built-in".equals(labelName)) return true;
        // Check whether any node's own name equals this label
        for (Node node : jenkins.getNodes()) {
            if (node.getNodeName().equals(labelName)) return true;
        }
        return false;
    }
}

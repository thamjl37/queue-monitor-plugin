package io.jenkins.plugins.queuemonitor;

import hudson.Extension;
import hudson.util.FormValidation;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Plugin-wide settings surfaced on Manage Jenkins → System.
 * All fields have safe defaults so the plugin works out-of-the-box.
 */
@Extension
public class GlobalConfig extends GlobalConfiguration {

    // -----------------------------------------------------------------------
    // Polling / retention
    // -----------------------------------------------------------------------

    /** How often (seconds) the background collector runs. Min 10 to avoid overloading. */
    private int pollIntervalSeconds = 30;

    /** How many hours of snapshot history to retain in memory. */
    private int retentionHours = 24;

    /** Maximum number of snapshots to keep (caps memory usage regardless of retention). */
    private int maxSnapshots = 2880; // 24 h × 2/min

    // -----------------------------------------------------------------------
    // Anomaly detection
    // -----------------------------------------------------------------------

    /** Multiplier over baseline average before a build is flagged as slow. */
    private double buildDurationAnomalyFactor = 1.5;

    /** Minimum baseline samples required before anomaly detection activates. */
    private int baselineSampleCount = 10;

    // -----------------------------------------------------------------------
    // Alert thresholds
    // -----------------------------------------------------------------------

    /** Queue depth per label above which an alert is raised (0 = disabled). */
    private int queueDepthAlertThreshold = 10;

    /** Consecutive polls a label must be saturated before alerting. */
    private int saturationAlertPollCount = 3;

    // -----------------------------------------------------------------------
    // Intelligent scheduling / scaling
    // -----------------------------------------------------------------------

    /** Enable dynamic label assignment for waiting jobs. */
    private boolean dynamicLabelEnabled = true;

    /** Enable resource-aware executor scaling. */
    private boolean executorScalingEnabled = true;

    /** Maximum executors that may be set on any single agent via auto-scaling. */
    private int maxExecutorsPerAgent = 20;

    /** Minimum executors to retain on any agent — scale-down stops here. */
    private int minExecutorsPerAgent = 1;

    /** Minimum free CPU % required before adding an executor. */
    private int scalingMinFreeCpuPercent = 20;

    /** Minimum free memory MB required before adding an executor. */
    private int scalingMinFreeMemoryMb = 256;

    /** Cooldown in seconds between successive scaling decisions on the same agent. */
    private int scalingCooldownSeconds = 300;

    // -----------------------------------------------------------------------
    // Singleton accessor
    // -----------------------------------------------------------------------

    public static GlobalConfig get() {
        return GlobalConfiguration.all().get(GlobalConfig.class);
    }

    public GlobalConfig() {
        load(); // restore persisted values
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
        req.bindJSON(this, json);
        save();
        return true;
    }

    // -----------------------------------------------------------------------
    // Getters / setters with validation
    // -----------------------------------------------------------------------

    public int getPollIntervalSeconds() { return pollIntervalSeconds; }
    @DataBoundSetter
    public void setPollIntervalSeconds(int v) {
        this.pollIntervalSeconds = Math.max(10, v);
    }

    public int getRetentionHours() { return retentionHours; }
    @DataBoundSetter
    public void setRetentionHours(int v) {
        this.retentionHours = Math.max(1, v);
    }

    public int getMaxSnapshots() { return maxSnapshots; }
    @DataBoundSetter
    public void setMaxSnapshots(int v) {
        this.maxSnapshots = Math.max(100, v);
    }

    public double getBuildDurationAnomalyFactor() { return buildDurationAnomalyFactor; }
    @DataBoundSetter
    public void setBuildDurationAnomalyFactor(double v) {
        this.buildDurationAnomalyFactor = Math.max(1.1, v);
    }

    public int getBaselineSampleCount() { return baselineSampleCount; }
    @DataBoundSetter
    public void setBaselineSampleCount(int v) {
        this.baselineSampleCount = Math.max(3, v);
    }

    public int getQueueDepthAlertThreshold() { return queueDepthAlertThreshold; }
    @DataBoundSetter
    public void setQueueDepthAlertThreshold(int v) {
        this.queueDepthAlertThreshold = Math.max(0, v);
    }

    public int getSaturationAlertPollCount() { return saturationAlertPollCount; }
    @DataBoundSetter
    public void setSaturationAlertPollCount(int v) {
        this.saturationAlertPollCount = Math.max(1, v);
    }

    public boolean isDynamicLabelEnabled() { return dynamicLabelEnabled; }
    @DataBoundSetter
    public void setDynamicLabelEnabled(boolean v) { this.dynamicLabelEnabled = v; }

    public boolean isExecutorScalingEnabled() { return executorScalingEnabled; }
    @DataBoundSetter
    public void setExecutorScalingEnabled(boolean v) { this.executorScalingEnabled = v; }

    public int getMaxExecutorsPerAgent() { return maxExecutorsPerAgent; }
    @DataBoundSetter
    public void setMaxExecutorsPerAgent(int v) {
        this.maxExecutorsPerAgent = Math.max(1, v);
    }

    public int getMinExecutorsPerAgent() { return minExecutorsPerAgent; }
    @DataBoundSetter
    public void setMinExecutorsPerAgent(int v) {
        this.minExecutorsPerAgent = Math.max(1, v);
    }

    public int getScalingMinFreeCpuPercent() { return scalingMinFreeCpuPercent; }
    @DataBoundSetter
    public void setScalingMinFreeCpuPercent(int v) {
        this.scalingMinFreeCpuPercent = Math.min(90, Math.max(5, v));
    }

    public int getScalingMinFreeMemoryMb() { return scalingMinFreeMemoryMb; }
    @DataBoundSetter
    public void setScalingMinFreeMemoryMb(int v) {
        this.scalingMinFreeMemoryMb = Math.max(128, v);
    }

    public int getScalingCooldownSeconds() { return scalingCooldownSeconds; }
    @DataBoundSetter
    public void setScalingCooldownSeconds(int v) {
        this.scalingCooldownSeconds = Math.max(60, v);
    }

    // -----------------------------------------------------------------------
    // Form validation
    // -----------------------------------------------------------------------

    public FormValidation doCheckPollIntervalSeconds(@QueryParameter int value) {
        return value < 10
            ? FormValidation.warning("Minimum recommended interval is 10 seconds to avoid controller overload.")
            : FormValidation.ok();
    }

    public FormValidation doCheckBuildDurationAnomalyFactor(@QueryParameter double value) {
        return value < 1.1
            ? FormValidation.error("Factor must be at least 1.1 (10% above baseline).")
            : FormValidation.ok();
    }

    public FormValidation doCheckMaxExecutorsPerAgent(@QueryParameter int value) {
        if (value > 50) return FormValidation.warning("Very high executor count may degrade agent stability.");
        if (value < 1)  return FormValidation.error("Must be at least 1.");
        return FormValidation.ok();
    }

    public FormValidation doCheckMinExecutorsPerAgent(@QueryParameter int value) {
        if (value < 1) return FormValidation.error("Must be at least 1.");
        return FormValidation.ok();
    }

    public FormValidation doCheckScalingMinFreeCpuPercent(@QueryParameter int value) {
        if (value < 5)  return FormValidation.error("Must be at least 5%.");
        if (value > 90) return FormValidation.error("Must be 90% or less.");
        return FormValidation.ok();
    }

    @Override
    public String getDisplayName() {
        return "Queue Depth Monitor";
    }
}

package io.jenkins.plugins.queuemonitor;

import hudson.Extension;
import hudson.util.FormValidation;
import hudson.util.Secret;
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

    /** Maximum number of snapshots to keep (caps memory usage regardless of retention). */
    private int maxSnapshots = 2880; // 24 h × 2/min

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

    /** Comma-separated agent (slave) names to exclude from executor scale-up/scale-down. */
    private String scalingExcludedAgents = "";

    // -----------------------------------------------------------------------
    // Build notification / webhook
    // -----------------------------------------------------------------------

    /** Send a JSON payload to an external endpoint after every build. */
    private boolean notificationEnabled = false;

    /** Full URL of the endpoint to POST build results to. */
    private String notificationEndpointUrl = "";

    /** Username for HTTP Basic authentication. */
    private String notificationUsername = "";

    /** Password for HTTP Basic authentication. */
    private Secret notificationPasswordSecret = Secret.fromString("");

    /** Bearer token for Authorization: Bearer … header. */
    private Secret notificationBearerTokenSecret = Secret.fromString("");

    /** Maximum lines of build log to include in the payload (0 = unlimited). */
    private int notificationMaxLogLines = 5000;

    // -----------------------------------------------------------------------
    // Queue depth trend detection / email alerting
    // -----------------------------------------------------------------------

    /** Send an email when a sustained increasing queue-depth trend is detected. */
    private boolean trendNotificationEnabled = false;

    /** Comma-separated email addresses to notify when a trend alert fires. */
    private String trendNotificationRecipients = "";

    /** Number of consecutive samples that must each exceed the previous one to count as a sustained trend. */
    private int trendSustainedSamples = 3;

    /** Latest sample's queue depth must reach at least this value before a trend alert is considered. */
    private int trendMinQueueDepth = 5;

    /** Minimum seconds between successive trend alert emails, even while the trend continues. */
    private int trendNotificationCooldownSeconds = 900;

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

    public int getMaxSnapshots() { return maxSnapshots; }
    @DataBoundSetter
    public void setMaxSnapshots(int v) {
        this.maxSnapshots = Math.max(100, v);
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

    public String getScalingExcludedAgents() { return scalingExcludedAgents; }
    @DataBoundSetter
    public void setScalingExcludedAgents(String v) {
        this.scalingExcludedAgents = v != null ? v.trim() : "";
    }

    /**
     * Parses {@link #scalingExcludedAgents} into a set of trimmed, non-empty agent names.
     * Recomputed on every call so edits via the config UI take effect without a restart.
     */
    public java.util.Set<String> getScalingExcludedAgentSet() {
        if (scalingExcludedAgents == null || scalingExcludedAgents.isBlank()) {
            return java.util.Collections.emptySet();
        }
        java.util.Set<String> result = new java.util.HashSet<>();
        for (String name : scalingExcludedAgents.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Form validation
    // -----------------------------------------------------------------------

    public FormValidation doCheckPollIntervalSeconds(@QueryParameter int value) {
        return value < 10
            ? FormValidation.warning("Minimum recommended interval is 10 seconds to avoid controller overload.")
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

    // -----------------------------------------------------------------------
    // Notification getters / setters
    // -----------------------------------------------------------------------

    public boolean isNotificationEnabled() { return notificationEnabled; }
    @DataBoundSetter
    public void setNotificationEnabled(boolean v) { this.notificationEnabled = v; }

    public String getNotificationEndpointUrl() { return notificationEndpointUrl; }
    @DataBoundSetter
    public void setNotificationEndpointUrl(String v) {
        this.notificationEndpointUrl = v != null ? v.trim() : "";
    }

    public String getNotificationUsername() { return notificationUsername; }
    @DataBoundSetter
    public void setNotificationUsername(String v) {
        this.notificationUsername = v != null ? v : "";
    }

    public Secret getNotificationPasswordSecret() { return notificationPasswordSecret; }
    @DataBoundSetter
    public void setNotificationPasswordSecret(Secret v) {
        this.notificationPasswordSecret = v != null ? v : Secret.fromString("");
    }

    public Secret getNotificationBearerTokenSecret() { return notificationBearerTokenSecret; }
    @DataBoundSetter
    public void setNotificationBearerTokenSecret(Secret v) {
        this.notificationBearerTokenSecret = v != null ? v : Secret.fromString("");
    }

    public int getNotificationMaxLogLines() { return notificationMaxLogLines; }
    @DataBoundSetter
    public void setNotificationMaxLogLines(int v) {
        this.notificationMaxLogLines = Math.max(0, v);
    }

    public FormValidation doCheckNotificationEndpointUrl(@QueryParameter String value) {
        if (value == null || value.isBlank()) return FormValidation.ok();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return FormValidation.error("URL must start with http:// or https://");
        }
        return FormValidation.ok();
    }

    // -----------------------------------------------------------------------
    // Trend alerting getters / setters
    // -----------------------------------------------------------------------

    public boolean isTrendNotificationEnabled() { return trendNotificationEnabled; }
    @DataBoundSetter
    public void setTrendNotificationEnabled(boolean v) { this.trendNotificationEnabled = v; }

    public String getTrendNotificationRecipients() { return trendNotificationRecipients; }
    @DataBoundSetter
    public void setTrendNotificationRecipients(String v) {
        this.trendNotificationRecipients = v != null ? v.trim() : "";
    }

    /**
     * Parses {@link #trendNotificationRecipients} into trimmed, non-empty email addresses.
     * Recomputed on every call so edits via the config UI take effect without a restart.
     */
    public java.util.List<String> getTrendNotificationRecipientList() {
        if (trendNotificationRecipients == null || trendNotificationRecipients.isBlank()) {
            return java.util.Collections.emptyList();
        }
        java.util.List<String> result = new java.util.ArrayList<>();
        for (String addr : trendNotificationRecipients.split(",")) {
            String trimmed = addr.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    public int getTrendSustainedSamples() { return trendSustainedSamples; }
    @DataBoundSetter
    public void setTrendSustainedSamples(int v) {
        this.trendSustainedSamples = Math.max(2, v);
    }

    public int getTrendMinQueueDepth() { return trendMinQueueDepth; }
    @DataBoundSetter
    public void setTrendMinQueueDepth(int v) {
        this.trendMinQueueDepth = Math.max(1, v);
    }

    public int getTrendNotificationCooldownSeconds() { return trendNotificationCooldownSeconds; }
    @DataBoundSetter
    public void setTrendNotificationCooldownSeconds(int v) {
        this.trendNotificationCooldownSeconds = Math.max(60, v);
    }

    public FormValidation doCheckTrendNotificationRecipients(@QueryParameter String value) {
        if (value == null || value.isBlank()) return FormValidation.ok();
        for (String addr : value.split(",")) {
            String trimmed = addr.trim();
            if (trimmed.isEmpty()) continue;
            if (!trimmed.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                return FormValidation.error("Invalid email address: " + trimmed);
            }
        }
        return FormValidation.ok();
    }

    public FormValidation doCheckTrendSustainedSamples(@QueryParameter int value) {
        if (value < 2) return FormValidation.error("Must be at least 2 to detect a trend.");
        return FormValidation.ok();
    }

    @Override
    public String getDisplayName() {
        return "Queue Depth Monitor";
    }
}

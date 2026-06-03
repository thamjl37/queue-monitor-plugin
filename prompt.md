# Queue Depth Monitor — Plugin Prompt / Functional Specification

This document describes every feature the plugin implements and how each is
handled in code.

---

## 1. Queue Depth Monitoring

**What it does**
Tracks the number of builds waiting in the Jenkins queue, broken down by the
agent label each build requires.

**How it works**
`QueueMetricsCollector` (extends `AsyncPeriodicWork`) runs every N seconds
(configurable, default 30 s). Each cycle it calls `jenkins.getQueue().getItems()`
and increments a per-label counter for every queued item. Results are stored in a
`QueueSnapshot` (immutable value object) and pushed into `MetricsStore`'s bounded
ring-buffer (`ConcurrentLinkedDeque`, capped by `maxSnapshots`).

A fallback pass also inserts every online agent's node name into `totalByLabel`
so the Label Status table is never empty when no labeled jobs are queued.

---

## 2. Executor Utilisation

**What it does**
Reports busy vs. total executors globally and per agent label.

**How it works**
Same `QueueMetricsCollector` cycle. Iterates `jenkins.getComputers()`, sums
`executor.isIdle()` flags, and stores global `busyExecutors / totalExecutors`
in `QueueSnapshot`. Per-label executor counts are computed by matching each
agent node against the known label set via `jenkins.getLabel(name).matches(node)`.

---

## 3. Build Duration Anomaly Detection

**What it does**
Flags builds that take significantly longer than their historical average
(e.g. due to a slow Nexus dependency download).

**How it works**
`BuildPickupListener.onCompleted()` calls
`MetricsStore.recordBuildDuration(jobName, durationMs)` to maintain a rolling
window of the last 100 durations per job. It then calls `checkDurationAnomaly()`
which computes `factor = actual / baseline`. If `factor >= anomalyFactor`
(configurable, default 1.5×) a `WARNING` log entry is written.
Anomaly detection is suppressed until `baselineSampleCount` (default 10) samples
exist for that job.

---

## 4. Executor Auto-Scaling

**What it does**
Automatically raises the executor count on a saturated agent (when its queue is
non-empty and all executors are busy) and lowers it when the queue empties,
subject to CPU and memory thresholds.

**How it works**
`SchedulingEngine` (singleton `@Extension`) is called at the end of every
`QueueMetricsCollector` cycle. For each label whose `isLabelSaturated()` is true
it finds a matching online agent, reads free CPU % and free RAM MB from
`Computer.getSystemProperties()` (via the Jenkins Monitoring API), and calls
`node.setNumExecutors(current + 1)` if both thresholds are met and the
per-agent cooldown (`scalingCooldownSeconds`) has elapsed. Scale-down runs when
`totalQueueDepth == 0` and `busyExecutors < totalExecutors`. Every decision is
recorded as a `ScalingEvent` in `MetricsStore`.

---

## 5. Dynamic Label Recommendations

**What it does**
When a job is waiting for a saturated label, suggests an alternative agent with
free executors that also carries the required label.

**How it works**
Inside `SchedulingEngine.evaluate()`, for each saturated label the engine scans
all online nodes for one whose label set includes the required label and which has
at least one idle executor. The recommendation is logged at `INFO` level with a
`[QueueMonitor] SUGGEST` prefix.

---

## 6. Build Pickup Tracking

**What it does**
Records which agent and label picked up each job, and how long the job waited in
the queue before execution began.

**How it works**
`BuildPickupListener.onStarted()` reads `run.getExecutor()` to obtain the
`Computer` and `Node`, then calls `resolveMatchedLabel()` which checks (in order):
persisted job→label hints, the job's `getAssignedLabel()`, and the node's own
self-label. Queue wait = `startTimeInMillis − scheduledTimeInMillis`. A
`PickupEvent` is stored in `MetricsStore.pickups`.

On `onCompleted()`, a `liveRefreshHints()` scan updates the stored label for
pipeline runs (where the sub-task label is often only visible once the run has
started) and writes the event to
`$JENKINS_HOME/queue-monitor-pickups.jsonl` (one JSON line per pickup).

---

## 7. Scaling Audit Log

**What it does**
Keeps a full history of every scale-up and scale-down decision.

**How it works**
`SchedulingEngine` creates a `ScalingEvent` (timestamp, agent name, old and new
executor count, reason, free CPU %, free RAM MB) and calls
`MetricsStore.addScalingEvent()` after every decision. The deque is bounded
the same way as snapshots.

---

## 8. Live Dashboard

**What it does**
An auto-refreshing web UI at `/queue-monitor` showing summary cards, a label
status table, a queue-depth trend chart, a pickup event table, and a scaling
audit table.

**How it works**
`QueueMonitorAction` (`@Extension`, implements `RootAction`) registers the URL
`/queue-monitor` and serves `index.jelly`. The page loads `dashboard.js`
(Stapler adjunct) which calls the four REST endpoints every 30 seconds with
`fetch()` and updates the DOM in-place (no page reload).

---

## 9. REST API

**What it does**
Exposes current and historical metrics as JSON for external tooling.

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/queue-monitor/apiSnapshot` | GET | Latest `QueueSnapshot` |
| `/queue-monitor/apiHistory?limit=N` | GET | Last N snapshots for charting |
| `/queue-monitor/apiPickups` | GET | All pickup events, newest-first |
| `/queue-monitor/apiScaling` | GET | All scaling events, newest-first |

**How it works**
Each `doApiXxx(StaplerRequest, StaplerResponse)` method in `QueueMonitorAction`
reads from `MetricsStore`, serialises to `net.sf.json.JSONObject/JSONArray`, and
writes the response with `Content-Type: application/json`.

---

## 10. Build Notifications (Webhook)

**What it does**
POSTs a structured JSON payload to a configurable HTTP endpoint after every
build completes, regardless of outcome (SUCCESS, FAILURE, UNSTABLE, ABORTED).

### Configuration (Manage Jenkins → System → Build Notifications)

| Field | Description |
|-------|-------------|
| Enable Build Notifications | Master on/off switch |
| Endpoint URL | `http://` or `https://` URL to POST to |
| Username | Username for HTTP Basic auth |
| Password | Password for HTTP Basic auth (AES-encrypted at rest) |
| Bearer Token | Token for `Authorization: Bearer …` (AES-encrypted at rest) |
| Max Log Lines | Maximum build-log lines included in the payload (0 = unlimited) |

Authentication is selected automatically:
- Username present → `Authorization: Basic base64(user:password)`
- Bearer Token only → `Authorization: Bearer <token>`
- Neither → no auth header

### Payload format

```json
{
  "jobName":     "my-pipeline",
  "buildNumber": 42,
  "jobUrl":      "https://jenkins.example.com/job/my-pipeline/42/",
  "startTime":   "2026-06-01T10:00:00Z",
  "endTime":     "2026-06-01T10:05:00Z",
  "status":      "SUCCESS",
  "log":         "Started by user …\n…",
  "agents": [
    {
      "slaveName": "linux-agent-1",
      "label":     "linux",
      "usedFrom":  "2026-06-01T10:00:01Z",
      "usedUntil": "2026-06-01T10:04:58Z"
    }
  ]
}
```

### How per-agent timing and labels are tracked

#### Pipeline builds — `AgentUsageTracker` (GraphListener)

`AgentUsageTracker` implements `GraphListener.Synchronous` (from `workflow-api`).
This interface fires synchronously on the pipeline execution thread each time a
new `FlowNode` is added to the pipeline graph.

Two events are used:

**`BlockStartNode`** — fires the instant a pipeline block body begins executing
on an agent. The tracker calls `Instant.now()` and stores it in a
`ConcurrentHashMap` keyed by `runId::blockStartNodeId`.

**`BlockEndNode`** — fires when the block body finishes. The tracker checks the
corresponding `BlockStartNode` for a `WorkspaceAction` (an interface in
`workflow-api` implemented only by `ExecutorStep` — the `node()` step). Its
presence is the discriminator: `stage()`, `parallel()`, `withEnv()` etc. do NOT
add a `WorkspaceAction`, so they are silently ignored.

When a node() block ends:
1. `WorkspaceAction.getNode()` → agent node name (set by the executor at
   allocation time, not from logs)
2. `ArgumentsAction.getArguments().get("label")` on the `BlockStartNode` →
   the exact label string passed to `node('label')` in the pipeline script
   (populated by the pipeline engine from the Groovy AST, not from logs)
3. If no explicit label was given (`node {}`), the agent's configured label
   string is looked up from `node.getLabelString()` in the Jenkins API
4. Start time is retrieved from the HashMap; `Instant.now()` is the end time
5. A `SlaveUsageDetail` is added to a per-run list in `completedByRun`

At `BuildPickupListener.onFinalized()`, `AgentUsageTracker.getAndRemoveUsage(runId)`
returns and removes the complete list.

#### Freestyle / non-pipeline fallback

For builds where `AgentUsageTracker` returns an empty list (no `node()` blocks
were observed — i.e. freestyle jobs), `BuildPickupListener.trackAgentStart()`
already stored the primary executor's agent name, label, and start time in a
`ConcurrentHashMap` at `onStarted()`. A single `SlaveUsageDetail` is created
covering the full build duration.

#### No log parsing

Labels, agent names, and timings are never derived from build log text.
All data comes from Jenkins API objects (FlowNode actions, executor metadata).

### HTTP dispatch — `BuildNotifier`

`BuildNotifier.send()` reads `GlobalConfig`, serialises the payload to JSON
(`net.sf.json.JSONObject`), opens an `HttpURLConnection`, applies the configured
auth header, POSTs the body, and logs the HTTP response code. Connection timeout
is 10 s; read timeout is 30 s. Non-2xx responses are logged as warnings but do
not affect the build result.

---

## 11. Configuration

All settings live in `GlobalConfig` (`GlobalConfiguration` extension), surfaced
under **Manage Jenkins → System → Queue Depth Monitor**. Settings are persisted
via XStream (Jenkins standard). Passwords and tokens are stored as
`hudson.util.Secret` (AES-encrypted).

### General

| Setting | Default | Effect |
|---------|---------|--------|
| Poll Interval (s) | 30 | `QueueMetricsCollector.getRecurrencePeriod()` |
| Retention Hours | 24 | Controls `evict()` in `MetricsStore` |
| Max Snapshots | 2880 | Hard cap on all ring buffers |
| Anomaly Factor | 1.5 | `checkDurationAnomaly()` multiplier |
| Baseline Sample Count | 10 | Minimum samples before anomaly activates |
| Queue Depth Alert Threshold | 10 | Warns in system log when exceeded |
| Saturation Alert Poll Count | 3 | Consecutive saturated polls before alerting |

### Executor Scaling

| Setting | Default | Effect |
|---------|---------|--------|
| Dynamic Label Enabled | true | Activates recommendations in `SchedulingEngine` |
| Executor Scaling Enabled | true | Activates scale-up/down in `SchedulingEngine` |
| Max Executors Per Agent | 20 | Ceiling for `setNumExecutors()` |
| Min Executors Per Agent | 1 | Floor for scale-down |
| Scaling Min Free CPU % | 20 | Required headroom before scale-up |
| Scaling Min Free Memory MB | 256 | Required headroom before scale-up |
| Scaling Cooldown (s) | 300 | Per-agent minimum gap between decisions |

---

## Class Map

| Class | Role |
|-------|------|
| `GlobalConfig` | All settings; `GlobalConfiguration` extension |
| `QueueMetricsCollector` | Background poller; `AsyncPeriodicWork` |
| `MetricsStore` | In-memory ring buffers for all metric types |
| `QueueSnapshot` | Immutable point-in-time state snapshot |
| `PickupEvent` | Single job-pickup record |
| `ScalingEvent` | Single scaling-decision record |
| `AgentResourceInfo` | CPU/memory reading for one agent |
| `SchedulingEngine` | Scale-up/down and label recommendations |
| `BuildPickupListener` | `RunListener`: pickup, anomaly, notification dispatch |
| `AgentUsageTracker` | `GraphListener.Synchronous`: per-node() block timing |
| `SlaveUsageDetail` | DTO: one agent-usage entry in the notification payload |
| `BuildNotifier` | Stateless HTTP POST utility |
| `QueueMonitorAction` | `RootAction` at `/queue-monitor`; REST + dashboard |

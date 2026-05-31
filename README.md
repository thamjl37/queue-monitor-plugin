# Queue Depth Monitor — Jenkins Plugin

A Jenkins plugin that monitors build queue depth, executor utilization, build-duration anomalies, and Nexus dependency delays across labeled agents. It also provides intelligent agent selection and resource-aware executor auto-scaling.

## Features

- **Queue depth monitoring** — tracks queue depth per agent label in real time
- **Executor utilization** — measures busy vs. total executors globally and per label
- **Build duration anomaly detection** — flags builds that exceed a configurable multiple of their baseline average
- **Executor auto-scaling** — scales executors up under queue pressure and back down when the queue is empty, respecting CPU/memory thresholds and a configurable cooldown
- **Dynamic label recommendations** — suggests label re-assignment to capable agents when scaling is not possible
- **Build pickup tracking** — records which agent and label picked up each job and how long it waited
- **Scaling audit log** — keeps a full history of every scale-up and scale-down decision
- **Live dashboard** — auto-refreshing UI at `/queue-monitor` with charts and audit tables
- **REST API** — JSON endpoints for external tooling integration

## Requirements

| Requirement | Version |
|-------------|---------|
| Jenkins | 2.541.2 or later |
| Java | 21 (JRE/JDK) |
| Maven | 3.8+ (build only) |

## Building

```bash
mvn clean package -DskipTests
```

The installable plugin artifact is produced at `target/queue-monitor-${project.version}.hpi`.

To run tests:

```bash
mvn test
```

> The auto-generated `InjectedTest` is excluded from the Surefire run due to a known `/closures/` 404 in the test harness against BOM 2.479.x. All hand-written tests in `src/test/` run normally.

## Installation

1. Build the `.hpi` file (see above) or download a release artifact.
2. In Jenkins: **Manage Jenkins → Plugins → Advanced → Deploy Plugin**.
3. Upload `queue-monitor.hpi` and restart Jenkins.

## Configuration

Navigate to **Manage Jenkins → System → Queue Depth Monitor** to configure:

| Setting | Default | Description |
|---------|---------|-------------|
| Poll Interval (seconds) | 30 | How often the background collector runs (minimum 10) |
| Retention Hours | 24 | How many hours of snapshot history to keep in memory |
| Max Snapshots | 2880 | Hard cap on snapshot count (memory budget) |
| Build Duration Anomaly Factor | 1.5 | Multiplier over baseline average to flag a slow build |
| Baseline Sample Count | 10 | Minimum samples before anomaly detection activates |
| Queue Depth Alert Threshold | 10 | Depth per label that triggers an alert (0 = disabled) |
| Saturation Alert Poll Count | 3 | Consecutive saturated polls before alerting |
| Dynamic Label Enabled | true | Enable dynamic label assignment recommendations |
| Executor Scaling Enabled | true | Enable resource-aware executor scaling |
| Max Executors Per Agent | 20 | Ceiling for auto-scaling |
| Min Executors Per Agent | 1 | Floor for scale-down |
| Scaling Min Free CPU % | 20 | Minimum free CPU before adding an executor |
| Scaling Min Free Memory MB | 256 | Minimum free RAM before adding an executor |
| Scaling Cooldown (seconds) | 300 | Minimum gap between scaling decisions on the same agent |

## Dashboard

Once installed, a **Queue Monitor** link appears in the Jenkins sidebar. The dashboard auto-refreshes every 30 seconds and is divided into the following sections.

---

### Summary Cards

![Summary Cards](docs/screenshots/summary.png)

The top of the dashboard displays three at-a-glance metrics:

| Card | Description |
|------|-------------|
| **Total Queue Depth** | The current number of builds waiting across all labels |
| **Executor Utilization** | Percentage of total executors that are actively running a build |
| **Busy / Total Executors** | Raw count of busy executors vs. the total available across all agents |

In the example above, 3 builds are queued, all 3 executors are busy (100% utilization), giving a 3/3 reading.

---

### Label Status

![Label Status](docs/screenshots/label-status.png)

A filterable, paginated table showing the current state of each agent label known to Jenkins.

| Column | Description |
|--------|-------------|
| **Label** | The Jenkins agent label (e.g. `windows`, `main&&windows`) |
| **Queue Depth** | Number of builds currently waiting for an executor with this label |
| **Busy Executors** | Number of executors actively running builds under this label |
| **Total Executors** | Total executors available for this label across all agents |
| **Saturated?** | Highlighted **YES** (red) when every executor for the label is busy and builds are still queued — this triggers the auto-scaler |

Rows are sorted with saturated labels first, then by descending queue depth, so the most critical labels are always visible at the top. Use the filter input and page-size selector to navigate large label sets.

---

### Queue Depth Trend

![Queue Depth Trend](docs/screenshots/trend-chart.png)

A canvas chart plotting the last 60 collected samples (up to the configured poll interval apart).

| Line | Description |
|------|-------------|
| **Red — Queue depth** | Total number of queued builds at each sample point |
| **Blue — Busy executors** | Number of executors actively running a build at each sample point |

The chart makes it easy to spot queue spikes and correlate them with executor activity. A sudden red spike followed by a blue rise indicates the auto-scaler added executors to absorb the backlog.

---

### Recent Execution Pickups

![Recent Execution Pickups](docs/screenshots/pickups.png)

A filterable, paginated table of the most recent build-pickup events — one row per build that left the queue and started executing.

| Column | Description |
|--------|-------------|
| **Time** | Timestamp when the build was picked up by an executor |
| **Job** | Name of the Jenkins job that was executed |
| **Agent** | The specific agent node that ran the build |
| **Label** | The matched agent label used to route the build |
| **Queue Wait** | How long the build waited in the queue before execution began (ms / s / min) |

Records are displayed newest-first. Use the **Filter by job** and **Filter by label** inputs to narrow results. In the example above, `testing_job` is consistently routing to the `main&&windows` label while `Test` routes to `windows`, both picking up within single-digit milliseconds — indicating healthy executor availability at that point.

---

### Executor Scaling Audit

![Executor Scaling Audit](docs/screenshots/scaling-audit.png)

A filterable, paginated audit log of every executor scaling decision made by the plugin.

| Column | Description |
|--------|-------------|
| **Time** | Timestamp of the scaling decision |
| **Agent** | The agent node whose executor count was changed |
| **Direction** | **Scale Up** (green) when executors were added; **Scale Down** (orange) when executors were reduced |
| **Executors** | The transition, e.g. `2 → 3` for a scale-up or `3 → 2` for a scale-down |
| **Reason** | Human-readable reason: `label saturation: <label>` for scale-up; `scale-down: queue empty` for scale-down |
| **CPU Free %** | Percentage of CPU that was free on the agent at the time of the decision |
| **Mem Free MB** | Free physical memory (MB) on the agent at the time of the decision |

In the example above, the agent scaled down from 6 → 2 executors over four consecutive poll cycles (queue empty, cooldown respected), then scaled back up from 2 → 3 when the `main&&windows` label became saturated again. Scale-ups only occur when both CPU and memory headroom meet the configured thresholds.

---

## REST API

All endpoints require at least `Jenkins.READ` permission.

| Endpoint | Description |
|----------|-------------|
| `GET /queue-monitor/apiSnapshot` | Current queue snapshot (JSON object) |
| `GET /queue-monitor/apiHistory?limit=N` | Last N snapshots for charting (JSON array) |
| `GET /queue-monitor/apiPickups` | All pickup events, newest-first (JSON array) |
| `GET /queue-monitor/apiScaling` | All scaling audit events, newest-first (JSON array) |

## Architecture

```
QueueMetricsCollector  (AsyncPeriodicWork)
    │  polls Jenkins every N seconds
    ├─► QueueSnapshot       — immutable point-in-time state
    ├─► MetricsStore        — ConcurrentLinkedDeque ring buffer (bounded)
    └─► SchedulingEngine    — evaluates queue; scales executors or recommends labels

BuildPickupListener    (RunListener)
    │  fires on job start and completion
    ├─► records PickupEvent (agent, label, wait time)
    └─► detects duration anomalies against per-job baseline

QueueMonitorAction     (RootAction @ /queue-monitor)
    ├─► serves the Jelly dashboard (index.jelly)
    └─► exposes REST endpoints (doApiSnapshot, doApiHistory, doApiPickups, doApiScaling)

GlobalConfig           (GlobalConfiguration @ Manage Jenkins → System)
    └─► all tuneable settings with safe defaults
```

## License

[MIT License](https://opensource.org/licenses/MIT)

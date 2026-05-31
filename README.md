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

Once installed, a **Queue Monitor** link appears in the Jenkins sidebar. The dashboard provides:

- Current queue depth and executor utilization (with label breakdown)
- Historical queue/utilization chart
- Build pickup event log (job, agent, label, wait time)
- Executor scaling audit log (direction, reason, CPU/memory readings)

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

# Queue Depth Monitor — Test Suite (UAT)

A **single parameterized pipeline script** that exercises every feature of the
Queue Depth Monitor plugin on the UAT Jenkins instance, including a PROD-scale
load simulation. It replaces the previous seven per-feature groovy jobs and the
external `rest-check` / `webhook-listener` scripts — all REST calls and
verification now run inside the pipeline itself via `sh` / `bat` / `powershell`
steps on the Jenkins agents.

```
test/
├── README.md                              ← this file
└── pipelines/
    └── queue-monitor-suite.groovy         ← the ONE script (suite + worker mode)
```

## Target environment (UAT)

| Node | Label | Notes |
|------|-------|-------|
| Controller | `built-in controller` (UI) | Jenkins treats this as two labels; the suite uses `built-in`, which always matches the controller. Needs ≥ 1 executor for the stress monitor. |
| Linux agent | `linux` | e.g. 4 executors |
| Windows agent | `windows` | e.g. 2 executors; needs `curl` (stock on Win10/Server 2019+) |
| Dashboard app | `http://devopsdashboard.com` (10.100.200.100) | Configure as the plugin's **Build Notification Endpoint URL**. Every worker build the suite launches POSTs its payload there, so Jenkins load is automatically duplicated onto the dashboard. |

## One-time setup

1. **Install the plugin** (`target/queue-monitor-*.hpi`) and restart.
2. **Plugin tuning** — *Manage Jenkins → System → Queue Depth Monitor*:
   - Poll Interval = **10** (and keep the suite's `POLL_INTERVAL` parameter in sync)
   - Executor Scaling Enabled = **true**, Max Executors Per Agent **>** current count,
     Scaling Cooldown = **30**
   - Build Notifications = **enabled**, Endpoint URL = the dashboard ingest URL
     (`http://devopsdashboard.com/...`), auth as required by the dashboard
3. **Create two Pipeline jobs, both with this same script pasted in:**
   - `queue-monitor-suite` — the orchestrator you run
   - `queue-monitor-worker` — launched by the suite as independent builds
     (pickups, anomaly baselines, notifications and stress volume all fire once
     per *build*, not per parallel branch, so a second job is required).
     `MODE=auto` detects "worker" in the job name — no per-job edits needed.
   - Run each job once after creation: it exits `NOT_BUILT` and registers the
     parameters. From then on use **Build with Parameters**.
   - Leave "Do not allow concurrent builds" **unchecked** on the worker job.
4. **REST credentials** — create a Username+Password credential holding a
   Jenkins user + API token and put its ID in the `CREDENTIALS_ID` parameter
   (leave blank only if anonymous users have READ).

## Selecting what to test

`TESTS` is a comma-separated list (or `all`). Tests always execute in a fixed
safe order (`rest → anomaly → queue → pickups → scaling → notify → stress`).

| Token | Plugin features covered | Verification |
|-------|------------------------|--------------|
| `rest` | REST API (all 4 endpoints), reachability + response time **from both agents** | automatic (HTTP 200 asserted; latency logged) |
| `anomaly` | Build-duration anomaly detection | seeds baseline + spike automatically; **manual**: check System Log for the `ALERT … x baseline` line |
| `queue` | Queue depth, executor utilization, Label Status, **saturation**, trend chart, **multi-label separation** (both labels loaded at once) | automatic (asserts `totalQueueDepth > 0`, both labels present, saturation flagged, history growing — via REST, sampled *during* the load) |
| `pickups` | Build pickup tracking (Recent Execution Pickups) | automatic (asserts ≥ `PICKUP_COUNT` rows for the worker job in `apiPickups`) |
| `scaling` | Executor auto-scaling, scaling audit log, dynamic label recommendations | automatic (asserts Scale Up rows; warns if Scale Down pending) + **manual**: confirm the agent's executor count physically changed |
| `notify` | Build notifications for SUCCESS / FAILURE / UNSTABLE / ABORTED | **manual**: confirm 4 payloads arrived on the dashboard with correct fields/auth |
| `stress` | PROD-scale load: controller stability, ring buffer, dashboard load duplication | automatic (controller REST latency budget + error count) + **manual**: UI responsiveness, `Max Snapshots` cap, dashboard received the webhook volume |

The suite prints a `TEST SUMMARY` (PASS / FAIL / MANUAL per test) and goes
`UNSTABLE` if anything failed.

## Recipes

| Goal | Parameters |
|------|-----------|
| Smoke test after install | `TESTS=rest` |
| Full functional UAT pass (default) | `TESTS=rest,queue,pickups,anomaly,scaling,notify` |
| PROD-scale load test | `TESTS=stress` (see sizing below) |
| Everything | `TESTS=all` |
| Single feature | e.g. `TESTS=scaling` |

## PROD-scale simulation on 2 agents

PROD runs **50–100 jobs in parallel on 50–100 agents**. The controller-side
cost of that is (a) scheduling/queue pressure and (b) 50–100 concurrent
*running* builds — and sleep-based test load is nearly free on an agent, so
2 UAT machines can emulate it:

- **Raise executor counts**: temporarily set each UAT agent to 25–50 executors,
  **or** set *Max Executors Per Agent* = 50 and let the plugin's auto-scaler
  raise them under saturation (which stress-tests scaling at PROD volume too).
- With default executors (4+2) the same total work still passes through
  Jenkins — the wave just runs longer as a soak test.

During each stress wave the suite runs three parallel tracks:

1. **independent-builds** — `STRESS_JOBS` (default 60, PROD range 50–100)
   worker builds alternating linux/windows; each also POSTs a
   `WORKER_LOG_LINES`-sized payload to the dashboard.
2. **queue-pressure** — `STRESS_FANOUT` extra `node()` branches keeping the
   queue deep across both labels.
3. **controller-monitor** — on the controller (`built-in`): every 15 s it
   measures `apiSnapshot` + core API response times and logs controller
   CPU/RAM. The stress test **fails** if any sample errors out or exceeds
   `LATENCY_BUDGET_MS` (default 3000 ms) — that is the "Jenkins does not hang
   or slow down" assertion. The load never targets the controller, so the
   monitor always has an executor.

Repeat with `STRESS_WAVES=2..3` for a sustained soak. Rough wave duration ≈
`(STRESS_JOBS×STRESS_JOB_SECONDS + STRESS_FANOUT×STRESS_HOLD) ÷ total executors`.

## Key parameters

| Parameter | Default | Meaning |
|-----------|---------|---------|
| `TESTS` | `rest,queue,pickups,anomaly,scaling,notify` | which features to run |
| `LINUX_LABEL` / `WINDOWS_LABEL` / `MASTER_LABEL` | `linux` / `windows` / `built-in` | UAT topology |
| `WORKER_JOB` | `queue-monitor-worker` | name of the second job |
| `CREDENTIALS_ID` | _(blank)_ | Jenkins API-token credential for REST checks |
| `POLL_INTERVAL` | `10` | must match the plugin's configured poll interval |
| `QUEUE_FANOUT` / `QUEUE_HOLD` | `12` / `120` | per-label branches / hold seconds |
| `PICKUP_COUNT` | `20` | independent builds for the pickups table |
| `BASELINE_RUNS` / `BASELINE_SECONDS` / `ANOMALY_SECONDS` | `10` / `5` / `30` | anomaly seeding + spike |
| `SCALE_LABEL` / `SCALE_FANOUT` / `SCALE_HOLD` / `DRAIN_WAIT` | `linux` / `16` / `180` / `120` | scaling phases |
| `STRESS_JOBS` / `STRESS_FANOUT` / `STRESS_WAVES` | `60` / `60` / `1` | PROD-scale volume |
| `LATENCY_BUDGET_MS` | `3000` | controller responsiveness pass/fail threshold |
| `WORKER_LOG_LINES` | `200` | log lines per worker build → webhook payload size |

## Ground-truth cross-checks

- **Build Executor Status** widget = real busy/idle → compare to plugin Busy/Total.
- **Build Queue** widget = real queued items → compare to Total Queue Depth.
- `apiSnapshot` JSON must match the Summary cards 1:1 at the same instant
  (the `queue` test asserts the REST side automatically).
- For scaling, open the **agent config** to confirm the executor count
  physically changed — not just logged.

## Gotchas

1. Parallel branches produce **one** Pickups row per pipeline build, not one per
   branch — that's why the worker job exists. Not a bug.
2. Run `anomaly` **before** `pickups`/`stress` (the suite's fixed order does
   this): later 45–60 s worker builds will themselves be flagged as anomalies
   against the 5 s baseline — extra ALERT lines in the System Log are expected.
3. The scaling resource gate reads the **controller's** CPU/RAM, not the
   agent's. If scale-up won't fire, lower *Scaling Min Free CPU %*.
4. The built-in node never scales and self-labels are excluded from Label
   Status — `SCALE_LABEL` must be a real agent label.
5. Keep holds ≥ 3 poll cycles (the suite auto-bumps `QUEUE_HOLD` if too short)
   or the collector may never sample the queued state.
6. If the suite and worker jobs live inside a folder, set `WORKER_JOB` to the
   full path (e.g. `folder/queue-monitor-worker`) so the pickups assertion can
   match the job name in `apiPickups`.
7. A forced `ABORTED` result is set programmatically; for a belt-and-braces
   check of real user aborts, cancel a long worker build in the UI once and
   confirm the dashboard still receives `status=ABORTED`.

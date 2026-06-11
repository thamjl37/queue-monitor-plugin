# Queue Depth Monitor — Test Jobs

Jenkins pipeline jobs and helper scripts for exercising every feature of the
Queue Depth Monitor plugin on a live Jenkins instance. This complements the
narrative plan in [`../TEST_CASES.txt`](../TEST_CASES.txt) with ready-to-paste
job scripts, organized **one driver per feature**.

## Folder layout

```
test/
├── README.md                         ← this file (feature → job map)
├── pipelines/
│   ├── 01-bulk-parallel.groovy           queue depth, utilization, label status,
│   │                                       saturation, trend, scaling audit
│   ├── 02-bulk-independent-builds.groovy  build pickup tracking (Pickups table)
│   ├── 03-duration-anomaly.groovy         build-duration anomaly detection
│   ├── 04-multi-label.groovy              multi-label separation + summary aggregation
│   ├── 05-scaling-saturation.groovy       auto-scaling + scaling audit + dynamic labels
│   ├── 06-stress-volume.groovy            trend accumulation, ring buffer, UI under load
│   └── 07-notification-matrix.groovy      build-notification webhook (all outcomes)
└── scripts/
    ├── rest-check.ps1 / rest-check.sh     hit all 4 REST endpoints, diff vs UI
    └── webhook-listener.ps1               local HTTP listener to inspect webhook POSTs
```

## One-time environment setup

1. **Install** `target/queue-monitor-3.0.0.hpi` → *Manage Jenkins → Plugins →
   Advanced → Deploy Plugin* → restart.
2. **Agents/labels** — minimum: label the built-in node `linux` with ~4
   executors (covers everything **except** scaling). Recommended: add 1–2
   inbound agents labelled `linux` / `windows` (e.g. 4 and 2 executors) to also
   exercise auto-scaling. *The built-in node never scales and self-labels are
   excluded from Label Status — always test scaling against a non-`built-in` label.*
3. **Tuning** — *Manage Jenkins → System → Queue Depth Monitor*:
   - Poll Interval → **10s** (minimum; samples transient states quickly)
   - Executor Scaling Enabled → **true**; Max Executors Per Agent → **8**;
     Scaling Cooldown → **30s**
   - Build Notifications **OFF** until you run job 07.
4. **Timing rules that make or break a test:**
   - Each branch must sleep **≥ 2 poll cycles** (≥ 30s at 10s polling; use 90–120s).
   - Fan-out must **exceed total executors** for the label, or queue depth and
     saturation never appear.

## Feature → test-job map

| # | Plugin feature | Driver job / script | Expected signal |
|---|----------------|---------------------|-----------------|
| 1 | **Queue depth monitoring** | `01-bulk-parallel` (FANOUT≫execs) | Summary *Total Queue Depth* ≈ FANOUT − totalExec; Label Status *Queue Depth* per label |
| 2 | **Executor utilization** | `01-bulk-parallel` | Summary *Utilization* 100%, *Busy/Total* = execs/execs |
| 3 | **Label Status table** | `01-bulk-parallel`, `04-multi-label` | one row per label with queue/busy/total |
| 4 | **Saturation detection & sorting** | `01-bulk-parallel` | *Saturated?* = **YES** (red), saturated row sorted to top; System Log ALERT after *Saturation Alert Poll Count* polls |
| 5 | **Queue Depth Trend chart** | `06-stress-volume` (or any sustained load) | red (queue) + blue (busy) lines accumulate; `apiHistory?limit=60` grows |
| 6 | **Multi-label separation** | `04-multi-label` (or two copies of job 01) | two distinct Label Status rows; Summary aggregates both |
| 7 | **Build pickup tracking** | `02-bulk-independent-builds` | ~COUNT newest-first rows: Job/Agent/Label/Queue Wait; later builds wait longer |
| 8 | **Build duration anomaly detection** | `03-duration-anomaly` | after baseline seeded, spike run logs `ALERT: Build … took N× baseline` |
| 9 | **Executor auto-scaling** | `05-scaling-saturation` | executor count physically changes on the agent (verify in agent config) |
| 10 | **Scaling audit log** | `05-scaling-saturation` | Scale Up `4→5…` reason `label saturation: <label>`; Scale Down reason `scale-down: queue empty`; CPU%/Mem columns filled |
| 11 | **Dynamic label recommendations** | `05-scaling-saturation` (scaling disabled / at ceiling) | recommendation logged when scale-up is not possible |
| 12 | **Live dashboard** | any load + browse `/queue-monitor` | sections render, auto-refresh, filters + pagination work, no UI errors (`06-stress-volume` for scale) |
| 13 | **REST API** | `scripts/rest-check.*` | `apiSnapshot` / `apiHistory` / `apiPickups` / `apiScaling` return JSON matching the UI 1:1 |
| 14 | **Build notifications** | `07-notification-matrix` + `scripts/webhook-listener.ps1` | POST per build for SUCCESS/FAILURE/UNSTABLE/ABORTED; correct auth header + payload fields |

## Suggested run order (fastest path to full coverage)

1. `scripts/rest-check.*` on a fresh install → **TC1** (plugin loads, REST reachable, zeros).
2. `01-bulk-parallel` FANOUT=30 SLEEP=120 LABEL=linux → features **1–4**; keep it running.
3. While #2 runs: `scripts/rest-check.*` again → **REST↔UI consistency (feature 13/TC7)**.
4. `06-stress-volume` (or just let #2 run 3–5 min) → **trend chart (feature 5)**.
5. `04-multi-label` (needs a 2nd label) → **multi-label separation (feature 6)**.
6. `02-bulk-independent-builds` (needs `sleeper-freestyle`) → **pickups (feature 7)**.
7. `03-duration-anomaly`: seed baseline ≥10 runs at SLEEP=5, then one SLEEP=30 → **anomaly (feature 8)**.
8. `05-scaling-saturation` against a real agent → **scaling + audit + dynamic labels (features 9–11)**.
9. Start `scripts/webhook-listener.ps1`, enable notifications, run `07-notification-matrix` once per RESULT → **notifications (feature 14)**.

## Ground-truth cross-checks

- **Build Executor Status** widget = real busy/idle → compare to plugin Busy/Total.
- **Build Queue** widget = real queued items → compare to Total Queue Depth.
- `apiSnapshot` JSON should match the Summary cards 1:1 at the same instant.
- For scaling, open the **agent config** to confirm the executor count physically
  changed — not just logged.

## Gotchas (so you don't misread pass/fail)

1. Parallel builds produce **one** Pickups row per pipeline build, not one per
   branch — by design. Use job 02 for pickup volume.
2. Parallel pickup queue-wait ≈ 0 (the flyweight starts instantly); the
   interesting per-branch waits do not generate PickupEvents.
3. The scaling resource gate reads the **controller's** CPU/RAM, not the agent's.
   If scale-up won't fire, lower *Scaling Min Free CPU %*.
4. The built-in node never scales; self-labels are excluded from Label Status.
5. First-run labels may briefly show `built-in` before the job→label hint
   resolves; `onCompleted` corrects it retroactively.
6. If branches finish inside one poll interval the collector may never sample the
   queued state — keep sleeps long.

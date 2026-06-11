// =============================================================================
// queue-monitor-suite.groovy — SINGLE unified test driver for the
// Queue Depth Monitor plugin.
//
// TARGET ENVIRONMENT (UAT):
//   * Controller:    built-in node (labelled "built-in controller" in the UI —
//                    Jenkins treats that as two labels; "built-in" always
//                    matches the controller, which is what MASTER_LABEL uses)
//   * Linux agent:   label "linux"
//   * Windows agent: label "windows"
//   * Dashboard app: http://devopsdashboard.com  (10.100.200.100) — configured
//                    as the plugin's Build Notification Endpoint URL, so every
//                    worker build this suite launches also pushes a payload to
//                    the dashboard (duplicates the Jenkins load onto it).
//
// ONE SCRIPT, TWO JOBS — paste this same script into BOTH:
//   1. Pipeline job "queue-monitor-suite"   -> the orchestrator you run.
//   2. Pipeline job "queue-monitor-worker"  -> launched by the suite to create
//      INDEPENDENT builds (pickup rows, anomaly baselines, notifications and
//      stress volume all fire once per BUILD, not per parallel branch).
//      MODE=auto detects "worker" in the job name and short-circuits.
//   Run each job once after creation (it exits NOT_BUILT and just registers
//   the parameters), then use "Build with Parameters".
//
// FEATURE SELECTION — TESTS is a comma-separated list (or "all"):
//   rest     REST API reachability + response time, exercised from BOTH agents
//            (sh+curl on linux, bat+curl on windows — no external scripts).
//   anomaly  Build-duration anomaly detection: seeds the baseline with short
//            worker builds, then one spike run. Run this BEFORE pickups/stress
//            so the worker job's baseline is clean.
//   queue    Core collection: queue depth, executor utilization, Label Status,
//            saturation, trend chart — BOTH labels loaded at once (also covers
//            multi-label separation). Self-verifies via apiSnapshot/apiHistory
//            while the load is still running.
//   pickups  "Recent Execution Pickups" volume via independent worker builds
//            alternating linux/windows. Self-verifies via apiPickups.
//   scaling  Saturate -> hold -> drain one label; self-verifies Scale Up /
//            Scale Down rows via apiScaling. Manually confirm the agent's
//            executor count physically changed.
//   notify   Build-notification webhook: one worker build per final result
//            (SUCCESS / FAILURE / UNSTABLE / ABORTED) -> 4 payloads on the
//            dashboard.
//   stress   PROD-scale simulation: STRESS_JOBS independent builds (PROD runs
//            50-100 parallel jobs) + STRESS_FANOUT queued branches across both
//            labels, repeated STRESS_WAVES times, while a monitor branch on
//            the controller samples REST latency and controller CPU/RAM every
//            15s. FAILS if any sample errors or exceeds LATENCY_BUDGET_MS.
//
// PROD-SCALE NOTE (50-100 jobs on 50-100 agents, reproduced on 2 agents):
//   The controller-side cost of PROD is (a) scheduling/queue pressure and
//   (b) 50-100 concurrent RUNNING builds. Sleep-based load is nearly free on
//   the agent, so to emulate 100 PROD executors either:
//     * temporarily raise each UAT agent to 25-50 executors, OR
//     * set "Max Executors Per Agent" = 50 and let the plugin's auto-scaler
//       raise them under saturation (which stress-tests scaling itself).
//   With default UAT executors (e.g. 4+2) the stress test still passes the
//   same TOTAL work through Jenkins — it just behaves as a longer soak.
//
// PREREQUISITES:
//   * Plugin tuning (Manage Jenkins -> System -> Queue Depth Monitor):
//       Poll Interval = 10, Executor Scaling Enabled = true,
//       Max Executors Per Agent > current count, Scaling Cooldown = 30,
//       Build Notifications = enabled, Endpoint URL = the dashboard ingest URL.
//   * A Jenkins user API token stored as a Username+Password credential; put
//     its ID in CREDENTIALS_ID (leave blank only if anonymous has READ).
//   * curl available on both agents (stock on Windows 10/Server 2019+).
//   * The controller (MASTER_LABEL) needs >= 1 executor for the stress
//     monitor branch — the stress load deliberately never targets it.
// =============================================================================

properties([parameters([
  choice (name: 'MODE',              choices: ['auto', 'suite', 'worker'],
          description: 'auto = run as worker if the job name contains "worker", else run the suite. Leave on auto.'),
  string (name: 'TESTS',             defaultValue: 'rest,queue,pickups,anomaly,scaling,notify',
          description: 'Comma-separated: rest,anomaly,queue,pickups,scaling,notify,stress — or "all". Tests always execute in a fixed safe order.'),

  // --- environment ---
  string (name: 'LINUX_LABEL',       defaultValue: 'linux',        description: '[env] Linux agent label'),
  string (name: 'WINDOWS_LABEL',     defaultValue: 'windows',      description: '[env] Windows agent label'),
  string (name: 'MASTER_LABEL',      defaultValue: 'built-in',     description: '[env] Controller label ("built-in" always matches the built-in node, even when the UI shows "built-in controller")'),
  string (name: 'WORKER_JOB',        defaultValue: 'queue-monitor-worker', description: '[env] Name of the worker job (this same script pasted into a second Pipeline job)'),
  string (name: 'BASE_URL',          defaultValue: '',             description: '[env] Jenkins base URL; blank = use env.JENKINS_URL'),
  string (name: 'CREDENTIALS_ID',    defaultValue: '',             description: '[env] Username+Password (API token) credential ID for REST calls; blank = anonymous'),
  string (name: 'POLL_INTERVAL',     defaultValue: '10',           description: '[env] Plugin Poll Interval in seconds — must match the value configured in Manage Jenkins'),
  string (name: 'DASHBOARD_URL',     defaultValue: 'http://devopsdashboard.com', description: '[env] Dashboard app (10.100.200.100); used in verification messages only'),

  // --- queue test ---
  string (name: 'QUEUE_FANOUT',      defaultValue: '12',           description: '[queue] Parallel branches PER LABEL; must exceed each label\'s executor count'),
  string (name: 'QUEUE_HOLD',        defaultValue: '120',          description: '[queue] Seconds each branch holds an executor (>= 3 poll cycles + 60)'),

  // --- pickups test ---
  string (name: 'PICKUP_COUNT',      defaultValue: '20',           description: '[pickups] Independent worker builds to launch (alternating linux/windows)'),
  string (name: 'PICKUP_SECONDS',    defaultValue: '45',           description: '[pickups] Work seconds per worker build'),

  // --- anomaly test ---
  string (name: 'BASELINE_RUNS',     defaultValue: '10',           description: '[anomaly] Baseline builds (= plugin "Baseline Sample Count")'),
  string (name: 'BASELINE_SECONDS',  defaultValue: '5',            description: '[anomaly] Work seconds per baseline build'),
  string (name: 'ANOMALY_SECONDS',   defaultValue: '30',           description: '[anomaly] Work seconds for the spike build (>= factor x baseline)'),

  // --- scaling test ---
  string (name: 'SCALE_LABEL',       defaultValue: 'linux',        description: '[scaling] NON-built-in label to saturate and scale'),
  string (name: 'SCALE_FANOUT',      defaultValue: '16',           description: '[scaling] Branches; must exceed the label\'s executor count'),
  string (name: 'SCALE_HOLD',        defaultValue: '180',          description: '[scaling] Seconds to hold saturation (allows several scale-ups)'),
  string (name: 'DRAIN_WAIT',        defaultValue: '120',          description: '[scaling] Idle seconds after drain to observe scale-down (respect cooldown)'),

  // --- stress test (PROD simulation) ---
  string (name: 'STRESS_JOBS',       defaultValue: '60',           description: '[stress] Independent worker builds per wave — PROD expects 50-100 parallel jobs'),
  string (name: 'STRESS_JOB_SECONDS',defaultValue: '60',           description: '[stress] Work seconds per stress worker build'),
  string (name: 'STRESS_FANOUT',     defaultValue: '60',           description: '[stress] Extra parallel branches per wave across both labels (queue pressure)'),
  string (name: 'STRESS_HOLD',       defaultValue: '120',          description: '[stress] Seconds each pressure branch holds an executor'),
  string (name: 'STRESS_WAVES',      defaultValue: '1',            description: '[stress] Waves to repeat (2-3 for a sustained soak)'),
  string (name: 'LATENCY_BUDGET_MS', defaultValue: '3000',         description: '[stress] Max acceptable controller REST response time in ms; any slower sample fails the test'),

  // --- worker mode (set by the suite when it triggers WORKER_JOB) ---
  string (name: 'WORK_SECONDS',      defaultValue: '60',           description: '[worker] Seconds of simulated work'),
  string (name: 'WORKER_NODE_LABEL', defaultValue: 'linux',        description: '[worker] Label to run the work on'),
  string (name: 'WORKER_LOG_LINES',  defaultValue: '200',          description: '[worker] Log lines to emit — sizes the webhook payload sent to the dashboard'),
  choice (name: 'FORCE_RESULT',      choices: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED'],
          description: '[worker] Final result to force (exercises all notification outcomes)')
])])

// --- first run only registers the parameters above ---
if (!params.MODE) {
  currentBuild.result = 'NOT_BUILT'
  echo 'Parameters registered. Run again with "Build with Parameters".'
  return
}

// --- shared state (script-binding so methods can see it) ---
BASE           = ((params.BASE_URL?.trim()) ?: (env.JENKINS_URL ?: 'http://localhost:8080')).replaceAll('/+$', '')
POLL           = params.POLL_INTERVAL.toInteger()
LINUX          = params.LINUX_LABEL
WIN            = params.WINDOWS_LABEL
MASTER         = params.MASTER_LABEL
results        = [:]      // test name -> PASS / FAIL: reason / MANUAL: instruction
latencySamples = []       // stress monitor samples
stressDone     = false

String mode = params.MODE
if (mode == 'auto') {
  mode = env.JOB_BASE_NAME.toLowerCase().contains('worker') ? 'worker' : 'suite'
}

// =============================================================================
// WORKER MODE — one independent build: N log lines + M seconds of "work",
// then force the requested final result. Drives pickups, anomaly baselines,
// notifications (incl. dashboard load) and stress volume.
// =============================================================================
if (mode == 'worker') {
  node(params.WORKER_NODE_LABEL) {
    withEnv(["QM_SECS=${params.WORK_SECONDS.toInteger()}",
             "QM_LINES=${params.WORKER_LOG_LINES.toInteger()}"]) {
      if (isUnix()) {
        sh '''#!/bin/sh
          echo "[worker] $(hostname): $QM_LINES log lines then ${QM_SECS}s of work"
          i=1
          while [ $i -le $QM_LINES ]; do echo "worker log line $i"; i=$((i+1)); done
          sleep $QM_SECS
        '''
      } else {
        bat '''@echo off
          echo [worker] %COMPUTERNAME%: %QM_LINES% log lines then %QM_SECS%s of work
          for /L %%i in (1,1,%QM_LINES%) do @echo worker log line %%i
          set /a N=%QM_SECS%+1
          ping -n %N% 127.0.0.1 >NUL
        '''
      }
    }
  }
  switch (params.FORCE_RESULT) {
    case 'UNSTABLE':
      unstable 'forced UNSTABLE (worker)'
      break
    case 'FAILURE':
      error 'forced FAILURE (worker)'
    case 'ABORTED':
      currentBuild.result = 'ABORTED'   // onFinalized still fires -> webhook sends status=ABORTED
      break
  }
  return
}

// =============================================================================
// SUITE MODE — orchestrate the selected tests in a fixed safe order.
// =============================================================================
def TEST_ORDER = ['rest', 'anomaly', 'queue', 'pickups', 'scaling', 'notify', 'stress']

def requested = params.TESTS.toLowerCase().replaceAll(/\s/, '').split(',').findAll { it }
if (requested.contains('all')) {
  requested = TEST_ORDER
}
def unknown = requested - TEST_ORDER
if (unknown) {
  error "Unknown TESTS token(s): ${unknown}. Valid: ${TEST_ORDER.join(', ')} or 'all'."
}
def toRun = TEST_ORDER.findAll { requested.contains(it) }

echo "Queue Monitor suite -> ${BASE} | tests: ${toRun.join(' -> ')} | labels: ${LINUX} / ${WIN} / ${MASTER}"

toRun.each { t ->
  runTest(t) {
    switch (t) {
      case 'rest':    runRestTest();    break
      case 'anomaly': runAnomalyTest(); break
      case 'queue':   runQueueTest();   break
      case 'pickups': runPickupsTest(); break
      case 'scaling': runScalingTest(); break
      case 'notify':  runNotifyTest();  break
      case 'stress':  runStressTest();  break
    }
  }
}

// --- summary ---
boolean anyFail = false
def summary = ['', '==================== TEST SUMMARY ====================']
TEST_ORDER.each { t ->
  if (results.containsKey(t)) {
    summary << "  ${t}: ${results[t]}"
    if (results[t].startsWith('FAIL')) { anyFail = true }
  }
}
summary << '======================================================'
echo summary.join('\n')
currentBuild.description = TEST_ORDER.findAll { results.containsKey(it) }
                                     .collect { "${it}:${results[it].tokenize(' :')[0]}" }
                                     .join(' ')
if (anyFail) {
  currentBuild.result = 'UNSTABLE'
}

// =============================================================================
// helpers
// =============================================================================

def runTest(String name, Closure body) {
  long t0 = System.currentTimeMillis()
  echo "########## [${name}] START ##########"
  try {
    body()
    if (!results[name]) { results[name] = 'PASS' }
  } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException fie) {
    throw fie
  } catch (e) {
    results[name] = "FAIL: ${e.message ?: e.toString()}"
  }
  echo "########## [${name}] END -> ${results[name]} (${(System.currentTimeMillis() - t0).intdiv(1000)}s) ##########"
}

// Wrap REST calls with credentials if configured; the shell snippets read
// QM_USER / QM_TOKEN and only add "-u" when QM_USER is non-empty.
def withApi(Closure body) {
  if (params.CREDENTIALS_ID?.trim()) {
    withCredentials([usernamePassword(credentialsId: params.CREDENTIALS_ID.trim(),
                                      usernameVariable: 'QM_USER',
                                      passwordVariable: 'QM_TOKEN')]) {
      body()
    }
  } else {
    withEnv(['QM_USER=', 'QM_TOKEN=']) {
      body()
    }
  }
}

// Parameter set the suite passes to every worker build.
def workerParams(String lbl, int secs, String result) {
  return [
    string(name: 'MODE',              value: 'worker'),
    string(name: 'WORKER_NODE_LABEL', value: lbl),
    string(name: 'WORK_SECONDS',      value: "${secs}"),
    string(name: 'WORKER_LOG_LINES',  value: params.WORKER_LOG_LINES),
    string(name: 'FORCE_RESULT',      value: result)
  ]
}

// =============================================================================
// rest — all 4 endpoints from BOTH agents, status + response time asserted
// =============================================================================

def runRestTest() {
  restCheckOn(LINUX)
  restCheckOn(WIN)
}

def restCheckOn(String label) {
  node(label) {
    withApi {
      withEnv(["QM_BASE=${BASE}"]) {
        if (isUnix()) {
          sh '''#!/bin/sh
            AUTH=""; [ -n "$QM_USER" ] && AUTH="-u $QM_USER:$QM_TOKEN"
            RC=0
            for EP in "apiSnapshot" "apiHistory?limit=60" "apiPickups" "apiScaling"; do
              OUT=$(curl $AUTH -s -o /dev/null -w "%{http_code} %{time_total}" "$QM_BASE/queue-monitor/$EP" || echo "000 -")
              CODE=$(echo "$OUT" | cut -d" " -f1)
              SECS=$(echo "$OUT" | cut -d" " -f2)
              echo "[rest] $EP HTTP $CODE in ${SECS}s (from $(hostname))"
              [ "$CODE" = "200" ] || RC=1
            done
            exit $RC
          '''
        } else {
          bat '''@echo off
            set "AUTH="
            if defined QM_USER set "AUTH=-u %QM_USER%:%QM_TOKEN%"
            curl %AUTH% -fsS -o NUL -w "[rest] apiSnapshot HTTP %%{http_code} in %%{time_total}s\\n" "%QM_BASE%/queue-monitor/apiSnapshot" || exit /b 1
            curl %AUTH% -fsS -o NUL -w "[rest] apiHistory HTTP %%{http_code} in %%{time_total}s\\n" "%QM_BASE%/queue-monitor/apiHistory?limit=60" || exit /b 1
            curl %AUTH% -fsS -o NUL -w "[rest] apiPickups HTTP %%{http_code} in %%{time_total}s\\n" "%QM_BASE%/queue-monitor/apiPickups" || exit /b 1
            curl %AUTH% -fsS -o NUL -w "[rest] apiScaling HTTP %%{http_code} in %%{time_total}s\\n" "%QM_BASE%/queue-monitor/apiScaling" || exit /b 1
            echo [rest] all endpoints OK from %COMPUTERNAME%
          '''
        }
      }
    }
  }
}

// =============================================================================
// anomaly — seed the worker job's baseline, then one spike run.
// Detection happens in the plugin's onCompleted; the ALERT lands in the
// Jenkins System Log, which a pipeline cannot read -> manual verification.
// =============================================================================

def runAnomalyTest() {
  int runs  = params.BASELINE_RUNS.toInteger()
  int base  = params.BASELINE_SECONDS.toInteger()
  int spike = params.ANOMALY_SECONDS.toInteger()
  for (int i = 1; i <= runs; i++) {
    echo "[anomaly] baseline run ${i}/${runs} (${base}s)"
    build job: params.WORKER_JOB, wait: true, propagate: false,
          parameters: workerParams(LINUX, base, 'SUCCESS')
  }
  echo "[anomaly] spike run (${spike}s) — should exceed factor x baseline"
  build job: params.WORKER_JOB, wait: true, propagate: false,
        parameters: workerParams(LINUX, spike, 'SUCCESS')
  results.anomaly = "MANUAL: Manage Jenkins -> System Log — expect \"[QueueMonitor] ALERT: Build '${params.WORKER_JOB} #N' took X.Xx baseline\""
}

// =============================================================================
// queue — load BOTH labels past saturation simultaneously, then verify the
// collector's view via REST while the load is still running. One linux
// executor is reserved up-front so the verification curl can always run.
// =============================================================================

def runQueueTest() {
  int fan    = params.QUEUE_FANOUT.toInteger()
  int settle = POLL * 3 + 5                       // let the collector sample the loaded state
  int hold   = Math.max(params.QUEUE_HOLD.toInteger(), settle + 60)

  def branches = [:]
  [LINUX, WIN].each { lbl ->
    for (int i = 0; i < fan; i++) {
      def idx = i
      def l = lbl
      branches["${l}-${idx}"] = {
        node(l) {
          echo "queue load ${l}-${idx} on ${env.NODE_NAME}"
          sleep hold
        }
      }
    }
  }

  node(LINUX) {  // reserved executor: counts as busy, lets us curl during full saturation
    branches['verify'] = {
      sleep settle
      verifyQueueSnapshot()
    }
    parallel branches
  }
}

def verifyQueueSnapshot() {
  withApi {
    withEnv(["QM_BASE=${BASE}", "QM_L1=${LINUX}", "QM_L2=${WIN}"]) {
      sh '''#!/bin/sh
        set -e
        AUTH=""; [ -n "$QM_USER" ] && AUTH="-u $QM_USER:$QM_TOKEN"
        curl $AUTH -fsS "$QM_BASE/queue-monitor/apiSnapshot" -o qm-snap.json
        echo "[queue] snapshot during load:"
        cat qm-snap.json; echo
        grep -Eq \'"totalQueueDepth":[1-9]\' qm-snap.json || { echo "[queue] FAIL: totalQueueDepth is 0 under load"; exit 1; }
        grep -q "\\"$QM_L1\\"" qm-snap.json || { echo "[queue] FAIL: label $QM_L1 missing from snapshot"; exit 1; }
        grep -q "\\"$QM_L2\\"" qm-snap.json || { echo "[queue] FAIL: label $QM_L2 missing from snapshot"; exit 1; }
        grep -o \'"saturationByLabel":{[^}]*}\' qm-snap.json | grep -q true || { echo "[queue] FAIL: no label reported saturated"; exit 1; }
        HIST=$(curl $AUTH -fsS "$QM_BASE/queue-monitor/apiHistory?limit=600" | grep -o \'"ts"\' | wc -l)
        echo "[queue] trend history samples: $HIST"
        [ "$HIST" -ge 2 ] || { echo "[queue] FAIL: trend history not accumulating"; exit 1; }
        echo "[queue] queue depth, both labels, saturation and trend all verified"
      '''
    }
  }
}

// =============================================================================
// pickups — independent worker builds alternating across both labels, then
// assert the pickup rows exist via apiPickups.
// =============================================================================

def runPickupsTest() {
  int count = params.PICKUP_COUNT.toInteger()
  int secs  = params.PICKUP_SECONDS.toInteger()
  def jobs = [:]
  for (int i = 0; i < count; i++) {
    def k = i
    def lbl = (k % 2 == 0) ? LINUX : WIN
    jobs["pickup-${k}"] = {
      build job: params.WORKER_JOB, wait: true, propagate: false,
            parameters: workerParams(lbl, secs, 'SUCCESS')
    }
  }
  parallel jobs

  node(LINUX) {
    withApi {
      withEnv(["QM_BASE=${BASE}", "QM_WORKER=${params.WORKER_JOB}", "QM_EXPECTED=${count}"]) {
        sh '''#!/bin/sh
          set -e
          AUTH=""; [ -n "$QM_USER" ] && AUTH="-u $QM_USER:$QM_TOKEN"
          N=$(curl $AUTH -fsS "$QM_BASE/queue-monitor/apiPickups" | grep -o "\\"job\\":\\"$QM_WORKER\\"" | wc -l)
          echo "[pickups] apiPickups rows for job \'$QM_WORKER\': $N (expected >= $QM_EXPECTED)"
          [ "$N" -ge "$QM_EXPECTED" ] || { echo "[pickups] FAIL: not enough pickup rows"; exit 1; }
          echo "[pickups] verified — also eyeball Queue Wait growth + job/label filters on the dashboard"
        '''
      }
    }
  }
}

// =============================================================================
// scaling — saturate SCALE_LABEL, hold, drain, then assert audit rows.
// =============================================================================

def runScalingTest() {
  int fan   = params.SCALE_FANOUT.toInteger()
  int hold  = params.SCALE_HOLD.toInteger()
  int drain = params.DRAIN_WAIT.toInteger()

  echo "[scaling] phase 1: saturating '${params.SCALE_LABEL}' with ${fan} branches for ${hold}s"
  def branches = [:]
  for (int i = 0; i < fan; i++) {
    def idx = i
    branches["sat-${idx}"] = {
      node(params.SCALE_LABEL) {
        sleep hold
      }
    }
  }
  parallel branches

  echo "[scaling] phase 2: queue drained — idling ${drain}s so the poller emits scale-down"
  sleep drain

  node(LINUX) {
    withApi {
      withEnv(["QM_BASE=${BASE}"]) {
        sh '''#!/bin/sh
          set -e
          AUTH=""; [ -n "$QM_USER" ] && AUTH="-u $QM_USER:$QM_TOKEN"
          curl $AUTH -fsS "$QM_BASE/queue-monitor/apiScaling" -o qm-scaling.json
          UP=$(grep -o \'"direction":"Scale Up"\' qm-scaling.json | wc -l)
          DOWN=$(grep -o \'"direction":"Scale Down"\' qm-scaling.json | wc -l)
          echo "[scaling] audit rows -> Scale Up: $UP, Scale Down: $DOWN"
          if [ "$UP" -lt 1 ]; then
            echo "[scaling] FAIL: no scale-up. Check: Executor Scaling Enabled, Max Executors Per Agent > current,"
            echo "          Scaling Cooldown (30s for tests), and the resource gate — it reads the CONTROLLER\'s"
            echo "          CPU/RAM, so lower \'Scaling Min Free CPU %\' if the controller is busy."
            exit 1
          fi
          [ "$DOWN" -ge 1 ] || echo "[scaling] WARN: no scale-down yet — cooldown may not have elapsed; re-check the Scaling Audit in a few minutes"
        '''
      }
    }
  }
  results.scaling = "PASS — also MANUALLY confirm the '${params.SCALE_LABEL}' agent's executor count physically changed (agent config page)"
}

// =============================================================================
// notify — one worker build per final result; payloads land on the dashboard.
// =============================================================================

def runNotifyTest() {
  ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED'].each { r ->
    echo "[notify] worker build forcing result ${r}"
    build job: params.WORKER_JOB, wait: true, propagate: false,
          parameters: workerParams(LINUX, 10, r)
  }
  results.notify = "MANUAL: confirm ${params.DASHBOARD_URL} (10.100.200.100) received 4 payloads " +
                   '(status SUCCESS/FAILURE/UNSTABLE/ABORTED) with jobName, buildNumber, ISO-8601 times, ' +
                   'log lines and agents[] populated. Non-2xx endpoint responses appear as warnings in the Jenkins System Log.'
}

// =============================================================================
// stress — PROD-scale simulation with controller health monitoring.
//   load    = STRESS_JOBS independent builds + STRESS_FANOUT queued branches
//             per wave, alternating linux/windows (controller never targeted)
//   monitor = on MASTER_LABEL: every 15s measure apiSnapshot + core API
//             latency and log controller CPU/RAM until the wave finishes
// Every worker build also POSTs its payload to the dashboard, duplicating the
// PROD notification volume there.
// =============================================================================

def runStressTest() {
  int jobs   = params.STRESS_JOBS.toInteger()
  int jobSec = params.STRESS_JOB_SECONDS.toInteger()
  int fan    = params.STRESS_FANOUT.toInteger()
  int hold   = params.STRESS_HOLD.toInteger()
  int waves  = params.STRESS_WAVES.toInteger()
  int budget = params.LATENCY_BUDGET_MS.toInteger()

  for (int w = 1; w <= waves; w++) {
    echo "[stress] ===== wave ${w}/${waves}: ${jobs} independent builds (${jobSec}s) + ${fan} branches (${hold}s) ====="
    stressDone = false
    def top = [:]
    top['load'] = {
      try {
        def load = [:]
        load['independent-builds'] = {
          def subs = [:]
          for (int i = 0; i < jobs; i++) {
            def k = i
            def lbl = (k % 2 == 0) ? LINUX : WIN
            subs["job-${k}"] = {
              build job: params.WORKER_JOB, wait: true, propagate: false,
                    parameters: workerParams(lbl, jobSec, 'SUCCESS')
            }
          }
          parallel subs
        }
        load['queue-pressure'] = {
          def subs = [:]
          for (int i = 0; i < fan; i++) {
            def k = i
            def lbl = (k % 2 == 0) ? LINUX : WIN
            subs["branch-${k}"] = {
              node(lbl) {
                sleep hold
              }
            }
          }
          parallel subs
        }
        parallel load
      } finally {
        stressDone = true
      }
    }
    top['controller-monitor'] = {
      try {
        monitorController()
      } catch (e) {
        echo "[stress] controller monitor failed: ${e.message} — does '${MASTER}' have a free executor?"
      }
    }
    parallel top
  }

  reportLatency(budget)
  postStressStats()
}

def monitorController() {
  node(MASTER) {
    withApi {
      withEnv(["QM_BASE=${BASE}"]) {
        while (!stressDone) {
          def line = isUnix() ? sampleUnix() : sampleWindows()
          if (line) {
            def p = line.split(/\s+/)   // QMLAT <snapCode> <snapMs> <rootCode> <rootMs>
            latencySamples << [snapCode: p[1], snap: p[2].toInteger(),
                               rootCode: p[3], root: p[4].toInteger()]
          }
          sleep 15
        }
      }
    }
  }
}

def sampleUnix() {
  def out = sh(returnStdout: true, script: '''#!/bin/sh
    AUTH=""; [ -n "$QM_USER" ] && AUTH="-u $QM_USER:$QM_TOKEN"
    SNAP=$(curl $AUTH -s -o /dev/null -w "%{http_code} %{time_total}" "$QM_BASE/queue-monitor/apiSnapshot" || echo "000 99")
    ROOT=$(curl $AUTH -s -o /dev/null -w "%{http_code} %{time_total}" "$QM_BASE/api/json?tree=mode" || echo "000 99")
    SNAP_MS=$(echo "$SNAP" | awk \'{printf "%d", $2 * 1000}\')
    ROOT_MS=$(echo "$ROOT" | awk \'{printf "%d", $2 * 1000}\')
    echo "[stress-monitor] controller load:"
    uptime || true
    free -m 2>/dev/null | head -2 || true
    echo "QMLAT $(echo "$SNAP" | cut -d" " -f1) $SNAP_MS $(echo "$ROOT" | cut -d" " -f1) $ROOT_MS"
  ''')
  echo out.trim()
  return out.readLines().find { it.startsWith('QMLAT') }
}

def sampleWindows() {
  def out = powershell(returnStdout: true, script: '''
    $h = @{}
    if ($env:QM_USER) {
      $pair = "$($env:QM_USER):$($env:QM_TOKEN)"
      $h.Authorization = "Basic " + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
    }
    function Hit($u) {
      $sw = [System.Diagnostics.Stopwatch]::StartNew()
      try { $r = Invoke-WebRequest -UseBasicParsing -Uri $u -Headers $h -TimeoutSec 60; $c = [int]$r.StatusCode }
      catch { $c = 0 }
      $sw.Stop()
      "$c $($sw.ElapsedMilliseconds)"
    }
    $snap = Hit "$($env:QM_BASE)/queue-monitor/apiSnapshot"
    $root = Hit "$($env:QM_BASE)/api/json?tree=mode"
    $cpu  = (Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average
    $os   = Get-CimInstance Win32_OperatingSystem
    Write-Output ("[stress-monitor] controller cpu {0}%  mem free {1} MB / {2} MB" -f $cpu, [math]::Round($os.FreePhysicalMemory/1KB), [math]::Round($os.TotalVisibleMemorySize/1KB))
    Write-Output "QMLAT $snap $root"
  ''')
  echo out.trim()
  return out.readLines().find { it.startsWith('QMLAT') }
}

def reportLatency(int budget) {
  if (latencySamples.isEmpty()) {
    results.stress = "FAIL: no controller latency samples collected — ensure label '${MASTER}' has >= 1 free executor"
    return
  }
  int n = latencySamples.size()
  int maxSnap = 0
  int maxRoot = 0
  long sumSnap = 0
  long sumRoot = 0
  int errors = 0
  latencySamples.each { s ->
    if (s.snapCode != '200' || s.rootCode != '200') { errors++ }
    maxSnap = Math.max(maxSnap, s.snap)
    maxRoot = Math.max(maxRoot, s.root)
    sumSnap += s.snap
    sumRoot += s.root
  }
  echo "[stress] controller health: ${n} samples | apiSnapshot avg ${sumSnap.intdiv(n)}ms max ${maxSnap}ms | core API avg ${sumRoot.intdiv(n)}ms max ${maxRoot}ms | errors ${errors} | budget ${budget}ms"
  if (errors > 0) {
    results.stress = "FAIL: ${errors}/${n} controller samples returned errors/timeouts under load"
  } else if (maxSnap > budget || maxRoot > budget) {
    results.stress = "FAIL: controller latency exceeded budget (snapshot max ${maxSnap}ms, core max ${maxRoot}ms > ${budget}ms)"
  } else {
    results.stress = "PASS (${n} samples, snapshot avg ${sumSnap.intdiv(n)}ms / max ${maxSnap}ms, no errors)"
  }
}

def postStressStats() {
  node(LINUX) {
    withApi {
      withEnv(["QM_BASE=${BASE}"]) {
        sh '''#!/bin/sh
          AUTH=""; [ -n "$QM_USER" ] && AUTH="-u $QM_USER:$QM_TOKEN"
          HIST=$(curl $AUTH -fsS "$QM_BASE/queue-monitor/apiHistory?limit=100000" | grep -o \'"ts"\' | wc -l)
          PICK=$(curl $AUTH -fsS "$QM_BASE/queue-monitor/apiPickups" | grep -o \'"job"\' | wc -l)
          SCAL=$(curl $AUTH -fsS "$QM_BASE/queue-monitor/apiScaling" | grep -o \'"direction"\' | wc -l)
          echo "[stress] post-stress store sizes -> history: $HIST snapshots, pickups: $PICK rows, scaling: $SCAL rows"
          echo "[stress] MANUAL: history count must stay <= the configured \'Max Snapshots\' (ring-buffer cap),"
          echo "         the dashboard UI must still render/filter/paginate responsively,"
          echo "         and the dashboard app should have received one webhook per worker build."
        '''
      }
    }
  }
}

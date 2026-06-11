// =============================================================================
// JOB 01 — bulk-parallel  (Pipeline job)
// =============================================================================
// PRIMARY DRIVER for: Queue Depth Monitoring, Executor Utilization,
//                     Label Status table, Saturation detection,
//                     Queue Depth Trend chart, Executor Scaling Audit.
//
// WHY THIS WORKS:
//   Each `node(LABEL){}` branch becomes a queue/executor PLACEHOLDER SUB-TASK
//   named "part of bulk-parallel #<N>" — exactly the pattern the collector keys
//   on (regex ^part of (.+) #\d+$). Fanning out beyond the label's executor
//   count forces real queue depth + saturation.
//
// HOW TO USE:
//   Create a Pipeline job named "bulk-parallel", paste this script.
//   Run with FANOUT comfortably > total executors for LABEL.
//
// KEY RULES:
//   * FANOUT must EXCEED total executors for LABEL, or you never see queue/saturation.
//   * SLEEP must span >= 2 poll cycles (>=30s at 10s polling; 120s is safe).
//
// EXPECT (FANOUT=30, 4-executor "linux" agent, poll=10s):
//   Summary  -> Total Queue Depth ~= 26, Busy/Total = 4/4, Utilization 100%.
//   Label Status row "linux": queue ~26, busy 4, total 4, Saturated? = YES.
// =============================================================================

properties([parameters([
  string(name: 'FANOUT', defaultValue: '30',    description: 'Number of parallel branches; MUST exceed total executors for LABEL'),
  string(name: 'SLEEP',  defaultValue: '120',   description: 'Seconds each branch holds an executor; span >=2 poll cycles'),
  string(name: 'LABEL',  defaultValue: 'linux', description: 'Agent label to saturate (never use "built-in")')
])])

def n = params.FANOUT.toInteger()
def branches = [:]
for (int i = 0; i < n; i++) {
  def idx = i
  branches["b-${idx}"] = {
    node(params.LABEL) {
      echo "branch ${idx} picked up on ${env.NODE_NAME}"
      sleep params.SLEEP.toInteger()
    }
  }
}
parallel branches

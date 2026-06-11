// =============================================================================
// JOB 04 — multi-label  (Pipeline job)
// =============================================================================
// PRIMARY DRIVER for: Multi-label separation in the Label Status table and the
//                     aggregation logic of the Summary cards.
//
// REQUIRES: at least two distinct labels with executors (e.g. "linux" + "windows").
//
// WHY THIS WORKS:
//   This single build fans out across TWO labels at once. Each branch becomes a
//   "part of multi-label #<N>" sub-task tagged with its label, so the collector
//   bins queue/busy counts into two separate Label Status rows simultaneously.
//
// EXPECT:
//   Label Status shows TWO distinct rows (linux and windows), each with its own
//   queue / busy / total. Summary cards aggregate BOTH labels.
//   Cross-check per-node executor status in "Build Executor Status".
//
// ALTERNATIVE (closer to TEST_CASES TC5): run Job 01 twice concurrently —
//   once LABEL=linux, once LABEL=windows — instead of this combined job.
// =============================================================================

properties([parameters([
  string(name: 'FANOUT_A', defaultValue: '20',      description: 'Branches on LABEL_A; exceed LABEL_A executors'),
  string(name: 'LABEL_A',  defaultValue: 'linux',   description: 'First label'),
  string(name: 'FANOUT_B', defaultValue: '10',      description: 'Branches on LABEL_B; exceed LABEL_B executors'),
  string(name: 'LABEL_B',  defaultValue: 'windows', description: 'Second label'),
  string(name: 'SLEEP',    defaultValue: '120',     description: 'Seconds each branch holds an executor (>=2 poll cycles)')
])])

def branches = [:]

def a = params.FANOUT_A.toInteger()
for (int i = 0; i < a; i++) {
  def idx = i
  branches["${params.LABEL_A}-${idx}"] = {
    node(params.LABEL_A) {
      echo "A branch ${idx} on ${env.NODE_NAME} (${params.LABEL_A})"
      sleep params.SLEEP.toInteger()
    }
  }
}

def b = params.FANOUT_B.toInteger()
for (int j = 0; j < b; j++) {
  def jdx = j
  branches["${params.LABEL_B}-${jdx}"] = {
    node(params.LABEL_B) {
      echo "B branch ${jdx} on ${env.NODE_NAME} (${params.LABEL_B})"
      sleep params.SLEEP.toInteger()
    }
  }
}

parallel branches

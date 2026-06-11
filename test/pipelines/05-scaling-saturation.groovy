// =============================================================================
// JOB 05 — scaling-saturation  (Pipeline job)
// =============================================================================
// PRIMARY DRIVER for: Executor Auto-Scaling, Scaling Audit log,
//                     Dynamic Label recommendations.
//
// HARD REQUIREMENTS (auto-scaling will NOT fire otherwise):
//   * Target a REAL, non-"built-in" agent. The built-in node NEVER scales and
//     self-labels are excluded from Label Status.
//   * Manage Jenkins -> System -> Queue Depth Monitor:
//       - Executor Scaling Enabled = true
//       - Max Executors Per Agent  > the agent's starting executor count (e.g. 8)
//       - Scaling Cooldown         = 30   (so events aren't 5 min apart)
//       - Poll Interval            = 10
//   * Resource gate caveat: the gate reads the CONTROLLER's CPU/RAM
//     (ManagementFactory.getOperatingSystemMXBean() in SchedulingEngine), NOT
//     the agent's. If scale-up never fires, the controller load may be failing
//     "Scaling Min Free CPU %" — lower that threshold for the test.
//
// SEQUENCE:
//   PHASE 1 (saturate): fan out beyond executors and HOLD -> queue stays full
//                       -> Scale Up rows "4->5..." reason "label saturation: <label>".
//   PHASE 2 (drain):    branches finish, queue empties -> after cooldown,
//                       Scale Down rows, reason "scale-down: queue empty".
//
// EXPECT:
//   Executor Scaling Audit shows Scale Up (green) then Scale Down (orange) rows
//   with CPU Free % / Mem Free MB columns populated.
// VERIFY:
//   Open the agent's config and confirm the executor count PHYSICALLY changed
//   (not just logged).  Cross-check GET /queue-monitor/apiScaling.
// =============================================================================

properties([parameters([
  string(name: 'FANOUT',     defaultValue: '24',    description: 'Branches; exceed agent executors so the label saturates'),
  string(name: 'HOLD',       defaultValue: '180',   description: 'PHASE 1 seconds to hold saturation (let several scale-ups happen)'),
  string(name: 'LABEL',      defaultValue: 'linux', description: 'NON-built-in agent label to scale'),
  string(name: 'DRAIN_WAIT', defaultValue: '90',    description: 'PHASE 2 idle seconds to observe scale-down after the queue empties')
])])

def n = params.FANOUT.toInteger()
def branches = [:]
for (int i = 0; i < n; i++) {
  def idx = i
  branches["sat-${idx}"] = {
    node(params.LABEL) {
      echo "saturating: branch ${idx} on ${env.NODE_NAME}"
      sleep params.HOLD.toInteger()
    }
  }
}

// PHASE 1 — saturate and hold (drives scale-UP audit rows)
parallel branches

// PHASE 2 — queue is now empty; idle so the poller observes the drain and
//           emits scale-DOWN audit rows (respecting the cooldown).
node(params.LABEL) {
  echo "draining: holding one idle executor while scale-down is observed"
  sleep params.DRAIN_WAIT.toInteger()
}

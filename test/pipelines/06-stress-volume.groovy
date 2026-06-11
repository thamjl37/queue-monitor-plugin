// =============================================================================
// JOB 06 — stress-volume  (Pipeline job)
// =============================================================================
// PRIMARY DRIVER for: Trend chart accumulation, ring-buffer bounds,
//                     dashboard responsiveness, table pagination/filtering.
//
// WHY THIS WORKS:
//   A large, long-held fan-out keeps queue depth high across many poll cycles,
//   so the Queue Depth Trend chart accumulates many sample points (red queue
//   line, blue busy line) and the snapshot ring buffer is exercised against its
//   "Max Snapshots" cap.
//
// EXPECT:
//   * Trend chart plots rising red then blue lines with multiple points.
//   * No UI errors; Label Status / Pickups / Scaling tables paginate + filter.
//   * Queue depth ~ (FANOUT - totalExecutors); dashboard stays responsive.
// VERIFY:
//   * GET /queue-monitor/apiHistory?limit=60 array grows over time.
//   * Snapshot count never exceeds the configured "Max Snapshots" (ring buffer).
//
// CAUTION: heavy. Lower FANOUT if the test box is small.
// =============================================================================

properties([parameters([
  string(name: 'FANOUT', defaultValue: '150',   description: 'Large fan-out for stress; queue depth ~ FANOUT - totalExecutors'),
  string(name: 'SLEEP',  defaultValue: '180',   description: 'Seconds each branch holds; long enough to span many poll cycles'),
  string(name: 'LABEL',  defaultValue: 'linux', description: 'Agent label')
])])

def n = params.FANOUT.toInteger()
def branches = [:]
for (int i = 0; i < n; i++) {
  def idx = i
  branches["s-${idx}"] = {
    node(params.LABEL) {
      sleep params.SLEEP.toInteger()
    }
  }
}
parallel branches

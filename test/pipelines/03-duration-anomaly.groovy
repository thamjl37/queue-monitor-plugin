// =============================================================================
// JOB 03 — duration-anomaly  (Pipeline job)
// =============================================================================
// PRIMARY DRIVER for: Build Duration Anomaly Detection.
//
// HOW IT WORKS (from BuildPickupListener.checkDurationAnomaly):
//   onCompleted compares each build's duration to a per-job rolling baseline.
//   Once at least "Baseline Sample Count" (default 10) samples exist, a build
//   whose duration >= baseline * "Build Duration Anomaly Factor" (default 1.5)
//   logs:
//     "[QueueMonitor] ALERT: Build '<job> #<N>' took N.N× baseline ... Possible Nexus delay."
//
// HOW TO USE — run this ONE pipeline job repeatedly:
//   1. Run with SLEEP=5  at least BASELINE_RUNS times (default 10) to seed the baseline.
//   2. Then run ONCE with SLEEP=30 (>= 1.5x the baseline) to trip the anomaly.
//   Tip: trigger the baseline runs quickly via "Build with Parameters" repeatedly,
//        or use Job 03b below to auto-seed the baseline in one click.
//
// VERIFY:
//   Manage Jenkins -> System Log  ->  look for the "took N× baseline" ALERT line.
// =============================================================================

properties([parameters([
  string(name: 'SLEEP', defaultValue: '5',     description: 'Seconds of work. Keep small for baseline runs; spike it for the anomaly run.'),
  string(name: 'LABEL', defaultValue: 'linux', description: 'Agent label to run on')
])])

node(params.LABEL) {
  echo "duration-anomaly run on ${env.NODE_NAME}, sleeping ${params.SLEEP}s"
  sleep params.SLEEP.toInteger()
}

// -----------------------------------------------------------------------------
// JOB 03b — duration-anomaly-seeder  (optional helper, separate Pipeline job)
// -----------------------------------------------------------------------------
// One click seeds the baseline then trips the anomaly. Paste as its own job.
// -----------------------------------------------------------------------------
//
//   properties([parameters([
//     string(name: 'BASELINE_RUNS', defaultValue: '10'),
//     string(name: 'BASELINE_SLEEP', defaultValue: '5'),
//     string(name: 'ANOMALY_SLEEP', defaultValue: '30'),
//     string(name: 'TARGET', defaultValue: 'duration-anomaly')
//   ])])
//
//   def runs = params.BASELINE_RUNS.toInteger()
//   for (int i = 0; i < runs; i++) {
//     build job: params.TARGET, wait: true,
//           parameters: [string(name: 'SLEEP', value: params.BASELINE_SLEEP)]
//   }
//   // Now the spike that should exceed factor x baseline:
//   build job: params.TARGET, wait: true,
//         parameters: [string(name: 'SLEEP', value: params.ANOMALY_SLEEP)]
// =============================================================================

// =============================================================================
// JOB 02 — bulk-independent-builds  (Pipeline job)  + sleeper-freestyle
// =============================================================================
// PRIMARY DRIVER for: Build Pickup Tracking ("Recent Execution Pickups" table).
//
// WHY A SEPARATE GENERATOR:
//   The Pickups table fires once PER PIPELINE BUILD (RunListener.onStarted),
//   NOT once per parallel branch. So one big parallel build (Job 01) yields
//   ONE pickup row. To fill the Pickups table with volume you must launch many
//   INDEPENDENT builds — that is what this job does.
//
// SETUP (do this first):
//   Create a FREESTYLE job named "sleeper-freestyle":
//     * Restrict where it runs  -> label: linux
//     * Build step              -> shell/batch:  sleep 60   (Windows: ping -n 60 127.0.0.1 >NUL)
//
// THEN create a Pipeline job named "bulk-independent-builds" with this script.
//
// EXPECT:
//   "Recent Execution Pickups" shows ~COUNT rows, newest-first, each with
//   Job / Agent / Label / Queue Wait. Because the freestyle agent has limited
//   executors, LATER builds wait longer -> larger Queue Wait values.
//   Cross-check: GET /queue-monitor/apiPickups ; the job/label filters work.
// =============================================================================

properties([parameters([
  string(name: 'COUNT',  defaultValue: '20',                description: 'Number of independent builds to launch'),
  string(name: 'TARGET', defaultValue: 'sleeper-freestyle', description: 'Freestyle job to invoke (must exist, label-restricted)')
])])

def jobs = [:]
def total = params.COUNT.toInteger()
for (int i = 0; i < total; i++) {
  def k = i
  jobs["t${k}"] = {
    build job: params.TARGET, wait: true
  }
}
parallel jobs

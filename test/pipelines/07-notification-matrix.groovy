// =============================================================================
// JOB 07 — notification-matrix  (Pipeline job)
// =============================================================================
// PRIMARY DRIVER for: Build Notification webhook (POST payload after EVERY build,
//                     regardless of outcome).
//
// SETUP (Manage Jenkins -> System -> Queue Depth Monitor -> Build Notifications):
//   * Enable Build Notifications = true
//   * Endpoint URL = a webhook.site URL OR a local listener (see test/scripts/)
//   * Pick ONE auth mode to exercise:
//       - Username + Password  -> expect  Authorization: Basic base64(user:pass)
//       - Bearer Token only    -> expect  Authorization: Bearer <token>
//       - Neither              -> expect  no Authorization header
//     (If a Username is present, Basic always wins.)
//
// WHY THIS WORKS:
//   onFinalized fires for every build outcome. This job parameterizes its final
//   RESULT so you can confirm the webhook POSTs for SUCCESS, FAILURE, UNSTABLE
//   and ABORTED alike, and that the payload fields are correct.
//
// EXPECT (inspect the received POST):
//   Content-Type: application/json; charset=UTF-8
//   Body has: jobName, buildNumber, jobUrl, startTime, endTime (ISO-8601 UTC),
//             status (matches RESULT), log (<= Max Log Lines), agents[] with
//             slaveName/label/usedFrom/usedUntil.
//   Auth header matches the configured mode. Plugin treats any 2xx as success.
//
// RUN MATRIX: run once per RESULT value: SUCCESS, FAILURE, UNSTABLE, ABORTED.
// =============================================================================

properties([parameters([
  choice(name: 'RESULT', choices: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED'], description: 'Final build result to force'),
  string(name: 'LABEL',  defaultValue: 'linux', description: 'Agent label to run on (populates agents[] in payload)')
])])

node(params.LABEL) {
  echo "notification-matrix on ${env.NODE_NAME}; forcing result=${params.RESULT}"
  // A few log lines so the payload "log" field is non-trivial.
  echo "line 1 — start of work"
  echo "line 2 — doing work"
  echo "line 3 — finishing"

  switch (params.RESULT) {
    case 'SUCCESS':
      echo "completing successfully"
      break
    case 'UNSTABLE':
      // mark build UNSTABLE without failing it
      unstable('forced UNSTABLE for notification test')
      break
    case 'ABORTED':
      // aborted result
      error('forced ABORT')   // see note below; for a true ABORTED, cancel the build in the UI
      break
    case 'FAILURE':
    default:
      error('forced FAILURE for notification test')
  }
}

// NOTE on ABORTED: the cleanest way to produce a genuine ABORTED status is to
// start a long build (e.g. add sleep 300 above) and click "Cancel" in the UI.
// The webhook still fires onFinalized with status=ABORTED.

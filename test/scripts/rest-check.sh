#!/usr/bin/env bash
# =============================================================================
# rest-check.sh — exercises all 4 REST endpoints (Linux/macOS)
# =============================================================================
# Drives: REST API feature + REST<->UI consistency (TEST_CASES TC1, TC7).
#
# Usage:
#   ./rest-check.sh http://localhost:8080 admin <api-token-or-password> [historyLimit]
#
# Needs curl. jq is optional (pretty-prints if present).
# Endpoints require at least Jenkins.READ.
# =============================================================================
set -euo pipefail

BASE="${1:-http://localhost:8080}"
USER="${2:-admin}"
TOKEN="${3:-}"
LIMIT="${4:-60}"

pretty() { if command -v jq >/dev/null 2>&1; then jq .; else cat; fi; }

hit() {
  local name="$1" path="$2"
  echo
  echo "=== ${name}  (${BASE}/queue-monitor/${path}) ==="
  curl -fsS -u "${USER}:${TOKEN}" "${BASE}/queue-monitor/${path}" | pretty \
    || echo "FAILED: ${name}"
}

hit "apiSnapshot" "apiSnapshot"
hit "apiHistory"  "apiHistory?limit=${LIMIT}"
hit "apiPickups"  "apiPickups"
hit "apiScaling"  "apiScaling"

echo
echo "Done. Compare apiSnapshot numbers to the Summary cards at the same instant."

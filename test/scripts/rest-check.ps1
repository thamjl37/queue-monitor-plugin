# =============================================================================
# rest-check.ps1 — exercises all 4 REST endpoints (Windows / PowerShell)
# =============================================================================
# Drives: REST API feature + REST<->UI consistency (TEST_CASES TC1, TC7).
#
# Usage:
#   .\rest-check.ps1 -BaseUrl http://localhost:8080 -User admin -Token <api-token-or-password>
#
# Endpoints require at least Jenkins.READ. Use a Jenkins API token (User ->
# Configure -> API Token) or password for -Token.
# =============================================================================
param(
  [string]$BaseUrl = "http://localhost:8080",
  [string]$User    = "admin",
  [string]$Token   = "",
  [int]   $HistoryLimit = 60
)

$pair   = "{0}:{1}" -f $User, $Token
$basic  = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
$headers = @{ Authorization = "Basic $basic" }

function Hit($name, $path) {
  $url = "$BaseUrl/queue-monitor/$path"
  Write-Host "`n=== $name  ($url) ===" -ForegroundColor Cyan
  try {
    $resp = Invoke-RestMethod -Uri $url -Headers $headers -Method GET
    $resp | ConvertTo-Json -Depth 6
  } catch {
    Write-Host "FAILED: $($_.Exception.Message)" -ForegroundColor Red
  }
}

Hit "apiSnapshot" "apiSnapshot"
Hit "apiHistory"  "apiHistory?limit=$HistoryLimit"
Hit "apiPickups"  "apiPickups"
Hit "apiScaling"  "apiScaling"

Write-Host "`nDone. Compare apiSnapshot numbers to the Summary cards at the same instant." -ForegroundColor Green

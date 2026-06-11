# =============================================================================
# webhook-listener.ps1 — minimal local HTTP listener for notification testing
# =============================================================================
# Drives: Build Notification webhook (TEST_CASES TC12).
#
# Prints method, headers (incl. Authorization), and the JSON body of every
# POST the plugin sends. Responds 200 so the plugin records success.
#
# Usage:
#   .\webhook-listener.ps1 -Port 9000
#   Then set the plugin's Endpoint URL to:  http://<this-host>:9000/
#   (use the controller's reachable IP/host, not localhost, if Jenkins is remote)
#
# Stop with Ctrl+C.
# =============================================================================
param([int]$Port = 9000)

$listener = [System.Net.HttpListener]::new()
$listener.Prefixes.Add("http://+:$Port/")
try {
  $listener.Start()
} catch {
  Write-Host "Could not bind http://+:$Port/ — try a different port or run as admin." -ForegroundColor Red
  Write-Host "Alternatively bind localhost only: replace '+' with 'localhost' in the script." -ForegroundColor Yellow
  throw
}
Write-Host "Listening on http://+:$Port/  (Ctrl+C to stop)" -ForegroundColor Green

while ($listener.IsListening) {
  $ctx = $listener.GetContext()
  $req = $ctx.Request
  Write-Host "`n===== $(Get-Date -Format o) =====" -ForegroundColor Cyan
  Write-Host "$($req.HttpMethod) $($req.Url.AbsolutePath)"
  foreach ($k in $req.Headers.AllKeys) { Write-Host ("  {0}: {1}" -f $k, $req.Headers[$k]) }

  $body = ""
  if ($req.HasEntityBody) {
    $reader = [System.IO.StreamReader]::new($req.InputStream, $req.ContentEncoding)
    $body = $reader.ReadToEnd(); $reader.Close()
  }
  Write-Host "--- body ---" -ForegroundColor DarkGray
  try { $body | ConvertFrom-Json | ConvertTo-Json -Depth 8 } catch { Write-Host $body }

  $resp = $ctx.Response
  $resp.StatusCode = 200
  $bytes = [Text.Encoding]::UTF8.GetBytes('{"ok":true}')
  $resp.ContentType = "application/json"
  $resp.OutputStream.Write($bytes, 0, $bytes.Length)
  $resp.OutputStream.Close()
}

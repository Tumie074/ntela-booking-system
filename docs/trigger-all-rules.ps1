# Triggers all four SOC detection rules so each one can be screenshotted.
# Run from the project folder in PowerShell:  .\docs\trigger-all-rules.ps1
# Prereq: docker compose up -d (all containers healthy).

$gw = "http://localhost:8080"

Write-Host "`n[Rule 1] Multiple Failed Logins - 6 bad logins for user 'demo-victim'"
1..6 | ForEach-Object {
    try { Invoke-RestMethod -Method Post -Uri "$gw/api/auth/login" -ContentType "application/json" `
        -Body '{"username":"demo-victim","password":"wrong-password"}' | Out-Null } catch { }
    Start-Sleep -Milliseconds 700   # stay under the gateway rate limiter (5/s)
}

Write-Host "[Rule 2] Unauthorised Endpoint Access - 7 calls with an invalid token"
1..7 | ForEach-Object {
    try { Invoke-RestMethod -Uri "$gw/api/resources" -Headers @{ Authorization = "Bearer not-a-real-token" } | Out-Null } catch { }
    Start-Sleep -Milliseconds 300
}

Write-Host "[Rule 3] Abnormal Request Rate - 40 requests within about 20 seconds"
1..40 | ForEach-Object {
    try { Invoke-RestMethod -Uri "$gw/api/resources" -Headers @{ Authorization = "Bearer not-a-real-token" } | Out-Null } catch { }
    Start-Sleep -Milliseconds 400
}

Write-Host "[Rule 4] Service Failure - stopping resource-service for 2.5 minutes"
docker compose stop resource-service | Out-Null
Write-Host "   (health checks every 30s record failures; waiting 150s...)"
Start-Sleep -Seconds 150
docker compose start resource-service | Out-Null

Write-Host "`nDone. Wait ~30 seconds, then open the SOC tab: expect up to 4 alerts."
Write-Host "Check with:  Invoke-RestMethod $gw/api/soc/alerts | Select ruleName, severity"
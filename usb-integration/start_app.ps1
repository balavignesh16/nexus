param([switch]$Ollama)
$ErrorActionPreference='Stop'
$repoRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $repoRoot
$config=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'config.local.json') -Raw | ConvertFrom-Json
if (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue) {
    throw 'Backend already running. Stop the bridge, then use python stop_nexus.py in this worktree before changing bench policy.'
}
$ledDevices=@($config.devices | Where-Object { $_.board -in @('ESP1','ESP3') } | ForEach-Object { [guid]$_.deviceId })
$servoDevices=@($config.devices | Where-Object { $_.board -eq 'ESP3' } | ForEach-Object { [guid]$_.deviceId })
$env:NEXUS_USB_REAL_LED_DEVICES=$ledDevices -join ','
$env:NEXUS_USB_REAL_SERVO_DEVICES=$servoDevices -join ','
$env:JAVA_TOOL_OPTIONS='-Xmx512m'
$env:MAVEN_OPTS='-Xmx256m'
if ($Ollama) {
    python start_phase6.py --ollama --model llama3.2:3b --allow-local-evidence --startup-timeout 150
} else {
    python start_phase6.py --startup-timeout 150
}
exit $LASTEXITCODE

# debug-still.ps1
param(
    [string]$pkg = "com.nitish.still"
)

# find adb on PATH or try common sdk location
$adb = "adb"
try {
    $ver = & $adb version 2>$null
} catch {
    $sdk = "$env:LOCALAPPDATA\Android\Sdk\platform-tools"
    $adb = Join-Path $sdk "adb.exe"
    if (-not (Test-Path $adb)) {
        Write-Error "adb not found. Please add platform-tools to PATH or install Android SDK platform-tools."
        exit 1
    }
}

$timestamp = (Get-Date).ToString("yyyyMMdd_HHmmss")
$logfile = ".\still-debug-$timestamp.log"
Write-Host "Using adb: $adb"
Write-Host "Log file will be: $logfile"
Write-Host ""

# 1) list devices
& $adb devices

# 2) clear log buffer
& $adb logcat -c

# 3) optional: show appops (GET_USAGE_STATS) and permissions
Write-Host "`n=== AppOps / permissions for $pkg ==="
& $adb shell "dumpsys appops $pkg | grep GET_USAGE_STATS -n" 2>$null
& $adb shell "dumpsys package $pkg | grep -A10 'requested permissions' " 2>$null

# 4) restart app safely
Write-Host "`n=== Restarting app ==="
& $adb shell am force-stop $pkg
Start-Sleep -Seconds 1
& $adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1

# 5) attempt to start foreground service (best-effort)
Write-Host "`n=== Attempting startForegroundService (may fail depending on API/device) ==="
try {
    & $adb shell "am start-foreground-service -n $pkg/.TimerService" 2>&1 | Tee-Object -FilePath $logfile -Append
} catch {
    Write-Host "start-foreground-service failed (this is okay on modern devices); continuing..."
}

# 6) dump services/process
Write-Host "`n=== Running processes / services (filtered) ==="
& $adb shell "ps -A | grep $pkg" 2>$null
& $adb shell "dumpsys activity services $pkg" 2>$null | Tee-Object -FilePath $logfile -Append

# 7) print some prefs (if app is debuggable)
Write-Host "`n=== Shared prefs (if accessible) ==="
& $adb shell "run-as $pkg cat /data/data/$pkg/shared_prefs/still_prefs.xml" 2>$null | Tee-Object -FilePath $logfile -Append

# 8) send test broadcast (simulate geofence)
Write-Host "`n=== Sending geofence test broadcast (simulate enter) ==="
& $adb shell "am broadcast -a com.nitish.still.GEOFENCE_EVENT --es is_inside_home true -n $pkg/.GeofenceBroadcastReceiver" 2>$null | Tee-Object -FilePath $logfile -Append

# 9) start captured filtered logcat to file (user stops with Ctrl-C)
Write-Host "`n=== Starting filtered logcat. Ctrl-C to stop. Logs appended to $logfile`n"
& $adb logcat -v time TimerService:D MainActivity:D GeofenceBR:D CameraCaptureActivity:D EyeClassifier:D *:S | Tee-Object -FilePath $logfile -Append

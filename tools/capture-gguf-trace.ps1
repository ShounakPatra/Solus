# Capture synchronized Perfetto and Logcat traces for Solus GGUF latency & UI analysis
[CmdletBinding()]
param(
    [int]$DurationSeconds = 30,
    [string]$OutputDir = "traces",
    [string]$DeviceSerial = $null
)

$ErrorActionPreference = "Stop"

# Ensure adb is accessible from PATH or standard Android SDK location
$adbExecutable = "adb"
if (-not (Get-Command "adb" -ErrorAction SilentlyContinue)) {
    $sdkAdb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools"
    if (Test-Path (Join-Path $sdkAdb "adb.exe")) {
        $env:PATH = "$env:PATH;$sdkAdb"
        $adbExecutable = Join-Path $sdkAdb "adb.exe"
    } else {
        Write-Error "adb command not found on PATH or in Android SDK platform-tools."
        exit 1
    }
} else {
    $adbExecutable = (Get-Command "adb").Source
}

# 1. Query ADB devices and validate device serial selection
$devicesOutput = & $adbExecutable devices
if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to query connected devices via 'adb devices' (exit code $LASTEXITCODE)."
    exit 1
}

$deviceLines = $devicesOutput | Where-Object { $_ -match "\bdevice\b" -and $_ -notmatch "List of devices" }
if (-not $deviceLines) {
    Write-Error "No active Android device found via 'adb devices'. Please connect your physical device."
    exit 1
}

$availableSerials = @($deviceLines | ForEach-Object { ($_ -split "\s+")[0].Trim() } | Where-Object { $_ -ne "" })

if ($DeviceSerial) {
    if ($availableSerials -notcontains $DeviceSerial) {
        Write-Error "Specified device serial '$DeviceSerial' not found in connected devices: ($($availableSerials -join ', '))"
        exit 1
    }
} else {
    if ($availableSerials.Count -gt 1) {
        Write-Error "Multiple Android devices connected ($($availableSerials -join ', ')). Please specify -DeviceSerial."
        exit 1
    }
    $DeviceSerial = $availableSerials[0]
}

Write-Host "Using target Android device serial: $DeviceSerial"

$adbArgs = @("-s", $DeviceSerial)

function Invoke-AdbCommand {
    param(
        [Parameter(Position = 0, Mandatory = $true)]
        [string[]]$Arguments,
        [Parameter(Mandatory = $false)]
        [string]$InputString = $null
    )

    $allArgs = $adbArgs + $Arguments
    if ($null -ne $InputString) {
        # Explicit stdin piping via ProcessStartInfo
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $adbExecutable
        $psi.Arguments = ($allArgs | ForEach-Object {
            if ($_ -match '[\s"]') {
                "`"" + ($_ -replace '"', '\"') + "`""
            } else {
                $_
            }
        }) -join " "
        $psi.UseShellExecute = $false
        $psi.RedirectStandardInput = $true
        $psi.RedirectStandardOutput = $false
        $psi.RedirectStandardError = $false
        $psi.CreateNoWindow = $true

        $proc = [System.Diagnostics.Process]::Start($psi)
        if (-not $proc) {
            throw "Failed to start process: $adbExecutable $($psi.Arguments)"
        }
        $writer = New-Object System.IO.StreamWriter($proc.StandardInput.BaseStream, [System.Text.UTF8Encoding]::new($false))
        $writer.Write($InputString)
        $writer.Flush()
        $writer.Close()

        $proc.WaitForExit()
        $exitCode = $proc.ExitCode
        if ($exitCode -ne 0) {
            throw "adb command failed with exit code ${exitCode} - $($Arguments -join ' ')"
        }
    } else {
        & $adbExecutable $allArgs
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) {
            throw "adb command failed with exit code ${exitCode} - $($Arguments -join ' ')"
        }
    }
}

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir | Out-Null
}

$traceOutFile = Join-Path $OutputDir "solus_trace_${timestamp}.perfetto-trace"
$logcatOutFile = Join-Path $OutputDir "solus_logcat_${timestamp}.log"
$configFile = Join-Path $PSScriptRoot "perfetto\gguf.pbtxt"

if (-not (Test-Path $configFile)) {
    Write-Error "Perfetto config file not found at $configFile"
    exit 1
}

$configContent = Get-Content $configFile -Raw
if ($DurationSeconds -ne 30) {
    $durationMs = $DurationSeconds * 1000
    $configContent = $configContent -replace 'duration_ms:\s*\d+', "duration_ms: $durationMs"
}

Write-Host "==> Clearing device logcat buffer..."
Invoke-AdbCommand @("logcat", "-c")

Write-Host "==> Starting synchronized Logcat and Perfetto trace ($DurationSeconds seconds)..."
Write-Host "    Perform your GGUF inference / benchmark actions now!"

# Start Logcat background process for this specific invocation
$logcatArgs = $adbArgs + @("logcat", "-v", "threadtime", "*:V")
$logcatProc = Start-Process -FilePath $adbExecutable -ArgumentList $logcatArgs -RedirectStandardOutput $logcatOutFile -PassThru -NoNewWindow
$logcatPid = $logcatProc.Id

try {
    # Run Perfetto synchronously with stdin config piped explicitly to adb stdin
    $remoteTracePath = "/data/misc/perfetto-traces/solus_trace_${timestamp}.perfetto-trace"
    Invoke-AdbCommand @("shell", "perfetto", "--txt", "-c", "-", "-o", $remoteTracePath) -InputString $configContent

    Write-Host "==> Trace capture completed. Pulling artifacts..."
    Invoke-AdbCommand @("pull", $remoteTracePath, $traceOutFile)

    # Require non-empty pulled trace before reporting success and deleting remote trace
    if (-not (Test-Path $traceOutFile)) {
        throw "Trace pull failed: destination file '$traceOutFile' was not created."
    }
    $traceFileSize = (Get-Item $traceOutFile).Length
    if ($traceFileSize -le 0) {
        throw "Trace pull failed: destination file '$traceOutFile' is empty (0 bytes)."
    }

    # Only delete remote trace after successful non-empty pull
    Invoke-AdbCommand @("shell", "rm", "-f", $remoteTracePath)
} finally {
    # Isolate and stop only the specific logcat process launched by this invocation
    if ($logcatProc -and -not $logcatProc.HasExited) {
        try {
            Stop-Process -Id $logcatPid -Force -ErrorAction SilentlyContinue
            $logcatProc.WaitForExit(3000) | Out-Null
        } catch {
            # Process may already have terminated
        }
    }
}

Write-Host "`nTrace capture successful!"
Write-Host "  Perfetto trace: $traceOutFile ($((Get-Item $traceOutFile).Length) bytes)"
Write-Host "  Logcat logs:    $logcatOutFile"
Write-Host "Open the trace file in https://ui.perfetto.dev to inspect execution slices and frame scheduling."

$appBuildDir = (Resolve-Path "app/build").Path
$extDex = (Resolve-Path "app/build/intermediates/dex/debug/mergeExtDexDebug" -ErrorAction SilentlyContinue)
$incPackage = (Resolve-Path "app/build/intermediates/incremental/packageDebug" -ErrorAction SilentlyContinue)

Write-Host "app/build: $appBuildDir"

if ($null -ne $extDex) {
    Write-Host "Found mergeExtDexDebug: $($extDex.Path)"
    if (-not $extDex.Path.StartsWith($appBuildDir, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "mergeExtDexDebug is outside app/build! Refusing to proceed."
    }
} else {
    Write-Host "mergeExtDexDebug not found or already quarantined."
}

if ($null -ne $incPackage) {
    Write-Host "Found packageDebug: $($incPackage.Path)"
    if (-not $incPackage.Path.StartsWith($appBuildDir, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "packageDebug is outside app/build! Refusing to proceed."
    }
} else {
    Write-Host "packageDebug not found or already quarantined."
}

$quarantineDir = Join-Path $appBuildDir "corrupt_dex_quarantine"
if (-not (Test-Path $quarantineDir)) {
    New-Item -ItemType Directory -Path $quarantineDir | Out-Null
    Write-Host "Created quarantine directory: $quarantineDir"
}

if ($null -ne $extDex -and (Test-Path $extDex.Path)) {
    $dest = Join-Path $quarantineDir "mergeExtDexDebug"
    if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
    Move-Item -Path $extDex.Path -Destination $dest -Force
    Write-Host "Successfully quarantined mergeExtDexDebug to: $dest"
}

if ($null -ne $incPackage -and (Test-Path $incPackage.Path)) {
    $dest = Join-Path $quarantineDir "packageDebug"
    if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
    Move-Item -Path $incPackage.Path -Destination $dest -Force
    Write-Host "Successfully quarantined packageDebug to: $dest"
}

Write-Host "Quarantine complete."

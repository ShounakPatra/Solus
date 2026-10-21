# prepare-native-tools.ps1
# Prepares host tools required for building Vulkan shaders in Solus llama.cpp

[CmdletBinding()]
param(
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Split-Path -Parent $ScriptDir
$ToolsDir = Join-Path $ProjectRoot "tools"
$LlvmMingwDir = Join-Path $ToolsDir "llvm-mingw"
$ZipUrl = "https://github.com/mstorsjo/llvm-mingw/releases/download/20261006/llvm-mingw-20261006-ucrt-x86_64.zip"
$ZipFile = Join-Path $ToolsDir "llvm-mingw-20261006-ucrt-x86_64.zip"

Write-Host "==> Checking host C++ compiler..." -ForegroundColor Cyan

$ExistingCompiler = Get-Command "cl.exe", "g++.exe", "clang++.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
if ($ExistingCompiler -and -not $Force) {
    Write-Host "Found host compiler: $($ExistingCompiler.Source)" -ForegroundColor Green
} else {
    $ClangBin = Join-Path $LlvmMingwDir "bin\clang++.exe"
    if (Test-Path $ClangBin) {
        Write-Host "Found portable LLVM-MinGW at: $ClangBin" -ForegroundColor Green
    } else {
        Write-Host "Downloading portable LLVM-MinGW 20261006 (UCRT x86_64)..." -ForegroundColor Yellow
        if (-not (Test-Path $ZipFile)) {
            Write-Host "Fetching $ZipUrl -> $ZipFile"
            curl.exe -L -f -o "$ZipFile" "$ZipUrl"
            if ($LASTEXITCODE -ne 0) {
                throw "Failed to download LLVM-MinGW package."
            }
        }

        Write-Host "Extracting LLVM-MinGW..." -ForegroundColor Yellow
        $TempExtract = Join-Path $ToolsDir "llvm_mingw_temp"
        if (Test-Path $TempExtract) { Remove-Item -Recurse -Force $TempExtract }
        Expand-Archive -Path $ZipFile -DestinationPath $TempExtract -Force

        $ExtractedSub = Get-ChildItem -Path $TempExtract -Directory | Select-Object -First 1
        if ($ExtractedSub) {
            if (Test-Path $LlvmMingwDir) { Remove-Item -Recurse -Force $LlvmMingwDir }
            Move-Item -Path $ExtractedSub.FullName -Destination $LlvmMingwDir -Force
        }
        Remove-Item -Recurse -Force $TempExtract
        if (Test-Path $ZipFile) { Remove-Item -Force $ZipFile }
        Write-Host "LLVM-MinGW provisioned at: $LlvmMingwDir" -ForegroundColor Green
    }
}

Write-Host "==> Checking NDK glslc..." -ForegroundColor Cyan
$NdkGlslc = $null
$PossibleNdkPaths = @(
    "C:\Users\shoun\AppData\Local\Android\Sdk\ndk\28.2.13676358\shader-tools\windows-x86_64\glslc.exe",
    "$env:ANDROID_HOME\ndk\28.2.13676358\shader-tools\windows-x86_64\glslc.exe",
    "$env:ANDROID_NDK_HOME\shader-tools\windows-x86_64\glslc.exe"
)

foreach ($p in $PossibleNdkPaths) {
    if ($p -and (Test-Path $p)) {
        $NdkGlslc = (Resolve-Path $p).Path
        break
    }
}

if (-not $NdkGlslc) {
    $SdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "C:\Users\shoun\AppData\Local\Android\Sdk" }
    $FoundGlslc = Get-ChildItem -Path "$SdkRoot\ndk" -Filter "glslc.exe" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($FoundGlslc) {
        $NdkGlslc = $FoundGlslc.FullName
    }
}

if ($NdkGlslc) {
    Write-Host "Found NDK glslc: $NdkGlslc" -ForegroundColor Green
} else {
    Write-Warning "glslc.exe not found in Android NDK! Vulkan shader compilation will fail if glslc is missing."
}

Write-Host "Native tools check complete." -ForegroundColor Cyan

param(
    [Parameter(Mandatory = $true)][string]$NdkPath,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [switch]$Check,
    [switch]$Test
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskPrivateRust = Join-Path $PSScriptRoot 'solus-rust'
$taskCargo = Get-Command cargo -ErrorAction SilentlyContinue
if ($null -eq $taskCargo -and (Test-Path (Join-Path $taskPrivateRust 'cargo/bin/cargo.exe'))) {
    $env:RUSTUP_HOME = Join-Path $taskPrivateRust 'rustup'
    $env:CARGO_HOME = Join-Path $taskPrivateRust 'cargo'
    $env:PATH = (Join-Path $env:CARGO_HOME 'bin') + ';' + $env:PATH
    $taskMingw = Join-Path $PSScriptRoot 'llvm-mingw/bin'
    if (Test-Path $taskMingw) { $env:PATH = $taskMingw + ';' + $env:PATH }
    $taskCargo = Get-Command cargo -ErrorAction SilentlyContinue
    $taskHostToolchain = (& (Join-Path $env:CARGO_HOME 'bin/rustc.exe') --print sysroot | Out-String).Trim()
    $taskBundledLinker = Join-Path $taskHostToolchain 'lib/rustlib/x86_64-pc-windows-gnu/bin/self-contained/x86_64-w64-mingw32-gcc.exe'
    if (Test-Path $taskBundledLinker) {
        $env:CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER = $taskBundledLinker
    }
}
if ($null -eq $taskCargo) { throw 'Install Rust and the aarch64-linux-android target; see docs/portable-models.md.' }
$taskToolchain = Join-Path $NdkPath 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$taskLinker = Join-Path $taskToolchain 'aarch64-linux-android28-clang.cmd'
if (!(Test-Path $taskLinker)) { throw "Android NDK compiler is missing: $taskLinker" }
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = $taskLinker
$env:CC_aarch64_linux_android = $taskLinker
$env:AR_aarch64_linux_android = Join-Path $taskToolchain 'llvm-ar.exe'
$env:CARGO_BUILD_JOBS = '4'
$taskManifest = Join-Path $taskRoot 'app/src/main/rust/Cargo.toml'
if ($Test) {
    & $taskCargo.Source test --locked --lib --manifest-path $taskManifest
} elseif ($Check) {
    & $taskCargo.Source check --locked --manifest-path $taskManifest --target aarch64-linux-android
} else {
    & $taskCargo.Source build --locked --manifest-path $taskManifest --target aarch64-linux-android --release
}
if ($LASTEXITCODE -ne 0) { throw "Candle compilation failed ($LASTEXITCODE)" }
if (!$Check -and !$Test) {
    $taskAbiDirectory = Join-Path $OutputDirectory 'arm64-v8a'
    New-Item -ItemType Directory -Path $taskAbiDirectory -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $taskRoot 'app/src/main/rust/target/aarch64-linux-android/release/libsolus_candle.so') -Destination $taskAbiDirectory -Force
}

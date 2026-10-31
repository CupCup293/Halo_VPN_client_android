# Сборка ядра: app/src/main/jniLibs/<abi>/libhalo.so — для телефона (arm64-v8a) и эмулятора (x86_64).
# Нужны Go 1.24+ и Android NDK (Android Studio → SDK Manager → SDK Tools → NDK (Side by side)).
# Запуск: powershell -ExecutionPolicy Bypass -File core\build.ps1
$ErrorActionPreference = 'Stop'
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$ndk = Get-ChildItem "$sdk\ndk" -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
if (-not $ndk) { throw "Не найден NDK в $sdk\ndk — поставьте его в Android Studio (SDK Manager → SDK Tools → NDK)" }
if (-not (Get-Command go -ErrorAction SilentlyContinue)) {
  $local = "$env:USERPROFILE\tools\go\bin"
  if (Test-Path "$local\go.exe") { $env:Path = "$local;$env:Path" } else { throw 'Не найден Go — поставьте с https://go.dev/dl/' }
}
$bin = "$($ndk.FullName)\toolchains\llvm\prebuilt\windows-x86_64\bin"
$targets = @(
  @{ abi = 'arm64-v8a'; arch = 'arm64'; cc = 'aarch64-linux-android29-clang.cmd' },
  @{ abi = 'x86_64'; arch = 'amd64'; cc = 'x86_64-linux-android29-clang.cmd' }
)
Push-Location $PSScriptRoot
try {
  go mod tidy
  if ($LASTEXITCODE) { throw 'go mod tidy не прошёл' }
  foreach ($t in $targets) {
    $out = "$PSScriptRoot\..\app\src\main\jniLibs\$($t.abi)\libhalo.so"
    New-Item -ItemType Directory -Force (Split-Path $out) | Out-Null
    $env:CC = "$bin\$($t.cc)"; $env:CGO_ENABLED = '1'; $env:GOOS = 'android'; $env:GOARCH = $t.arch
    # cmfa — как у Clash Meta for Android: ядро не читает /data/system/packages.xml (приложению туда нельзя),
    # раздельный туннель по приложениям делает сам VpnService.
    go build -buildmode=c-shared -tags with_gvisor,cmfa -trimpath -ldflags '-s -w -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384' -o $out .
    if ($LASTEXITCODE) { throw "Сборка $($t.abi) не прошла" }
    Remove-Item ($out -replace '\.so$', '.h') -ErrorAction SilentlyContinue
    "Готово: $($t.abi) ({0:N1} МБ)" -f ((Get-Item $out).Length / 1MB)
  }
} finally { Pop-Location }

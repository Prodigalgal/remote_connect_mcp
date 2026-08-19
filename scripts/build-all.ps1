$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$go = Get-Command go -ErrorAction SilentlyContinue
if (-not $go) {
    $go = Get-ChildItem -Path "Go installation directory" -Filter go.exe -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -like "*\bin\go.exe" } |
        Select-Object -First 1
}
if (-not $go) {
    throw "Go was not found."
}
$goExe = if ($go.Source) { $go.Source } else { $go.FullName }
$version = "0.1.0"
$targets = @(
    @{ OS = "windows"; Arch = "amd64"; Suffix = ".exe" },
    @{ OS = "windows"; Arch = "arm64"; Suffix = ".exe" },
    @{ OS = "linux"; Arch = "amd64"; Suffix = "" },
    @{ OS = "linux"; Arch = "arm64"; Suffix = "" }
)

Push-Location $root
try {
    & $goExe test -mod=mod ./...
    if ($LASTEXITCODE -ne 0) { throw "Tests failed." }
    foreach ($target in $targets) {
        $env:CGO_ENABLED = "0"
        $env:GOOS = $target.OS
        $env:GOARCH = $target.Arch
        $directory = Join-Path $root "dist\$($target.OS)-$($target.Arch)"
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
        $output = Join-Path $directory "remote_connect_mcp$($target.Suffix)"
        & $goExe build -mod=mod -trimpath -ldflags "-s -w -X main.version=$version" -o $output ./cmd/remote_connect_mcp
        if ($LASTEXITCODE -ne 0) { throw "Build failed for $($target.OS)/$($target.Arch)." }
        Write-Host "Built $output"
    }
}
finally {
    Pop-Location
    Remove-Item Env:GOOS, Env:GOARCH, Env:CGO_ENABLED -ErrorAction SilentlyContinue
}

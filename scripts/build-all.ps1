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
$version = (& $goExe env GOVERSION).Trim()
$gitVersion = git describe --tags --always --dirty 2>$null
if ($LASTEXITCODE -eq 0 -and $gitVersion) {
    $version = $gitVersion.Trim()
}
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
        foreach ($component in @("center", "agent")) {
            $output = Join-Path $directory "remote-connect-mcp-$component$($target.Suffix)"
            & $goExe build -mod=mod -trimpath -ldflags "-s -w -X main.version=$version" -o $output "./cmd/remote-connect-mcp-$component"
            if ($LASTEXITCODE -ne 0) { throw "Build failed for $component $($target.OS)/$($target.Arch)." }
            Write-Host "Built $output"
        }
    }
}
finally {
    Pop-Location
    Remove-Item Env:GOOS, Env:GOARCH, Env:CGO_ENABLED -ErrorAction SilentlyContinue
}

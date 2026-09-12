$ErrorActionPreference = 'Stop'

if ($env:GITHUB_ACTIONS -ne 'true') {
    throw 'Local Java/React compilation is disabled. Push a branch or java-vX.Y.Z tag and let GitHub Actions build it.'
}

$root = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $root 'java\gradlew.bat'
if (-not (Test-Path -LiteralPath $gradle)) { throw "Java Gradle Wrapper not found: $gradle" }
Push-Location (Join-Path $root 'java')
try {

Write-Host '== Java tests ==' -ForegroundColor Cyan
& $gradle test --no-daemon

Write-Host '== JVM artifacts ==' -ForegroundColor Cyan
& $gradle :center:bootJar :agent:jar --no-daemon

Write-Host '== React production build ==' -ForegroundColor Cyan
pnpm --dir (Join-Path $root 'web') install --frozen-lockfile
pnpm --dir (Join-Path $root 'web') build
} finally {
    Pop-Location
}

Write-Host 'Java/React migration build completed.' -ForegroundColor Green

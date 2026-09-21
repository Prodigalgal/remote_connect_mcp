[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$CenterUrl,
    [Parameter(Mandatory = $true)][string]$EnrollmentToken,
    [Parameter(Mandatory = $true)][ValidatePattern("^[A-Za-z_][A-Za-z0-9_.-]{0,63}$")][string]$AgentName,
    [string]$HostId = "",
    [Parameter(Mandatory = $true)][ValidatePattern("^v[0-9A-Za-z][0-9A-Za-z.-]*$")][string]$Version,
    [ValidatePattern("^[A-Za-z0-9_.-]{1,128}$")][string]$ReleaseTag = "",
    [ValidateSet("command", "full")][string]$Mode = "command",
    [switch]$Desktop,
    [switch]$Browser,
    [ValidatePattern("^[A-Za-z_][A-Za-z0-9_.-]{0,63}$")][string]$DesktopUser = "",
    [ValidateSet("playwright", "patchright", "comoufox")][string]$BrowserEngine = "playwright",
    [ValidateSet("chromium", "firefox", "webkit")][string]$BrowserName = "chromium",
    [ValidateSet("0", "1")][string]$BrowserHeadless = "1",
    [string]$NodePath = "",
    [string]$PlaywrightVersion = "1.63.0",
    [string]$InstallRoot = "$env:ProgramFiles\Remote Connect MCP Agent",
    [string]$StateDir = "$env:ProgramData\RemoteConnectMCPAgent",
    [switch]$ReEnroll,
    [switch]$InternalChild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

function Resolve-PowerShell7Path {
    $candidates = [System.Collections.Generic.List[string]]::new()
    if (-not [string]::IsNullOrWhiteSpace($env:REMOTE_CONNECT_MCP_PWSH_PATH)) {
        [void]$candidates.Add($env:REMOTE_CONNECT_MCP_PWSH_PATH)
    }
    foreach ($package in @(Get-AppxPackage -Name Microsoft.PowerShell -ErrorAction SilentlyContinue |
        Sort-Object Version -Descending | Select-Object -First 1)) {
        if (-not [string]::IsNullOrWhiteSpace($package.InstallLocation)) {
            [void]$candidates.Add((Join-Path $package.InstallLocation 'pwsh.exe'))
        }
    }
    [void]$candidates.Add((Join-Path $env:ProgramFiles 'PowerShell\7\pwsh.exe'))
    foreach ($commandName in @('pwsh.exe', 'pwsh')) {
        $command = Get-Command $commandName -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($command -and $command.Source) { [void]$candidates.Add([string]$command.Source) }
    }
    foreach ($candidate in $candidates) {
        try {
            $resolved = (Resolve-Path -LiteralPath $candidate -ErrorAction Stop).Path
            if (Test-Path -LiteralPath $resolved -PathType Leaf) { return $resolved }
        } catch { }
    }
    throw 'PowerShell 7 was not found. Install Microsoft.PowerShell 7 or set REMOTE_CONNECT_MCP_PWSH_PATH to pwsh.exe.'
}

function ConvertTo-CliArgument {
    param([AllowEmptyString()][string]$Value)
    if ($null -eq $Value) { return '""' }
    if ($Value -notmatch '[\s"]') { return $Value }
    return '"' + $Value.Replace('"', '\"') + '"'
}

function Invoke-ElevatedPowerShell7 {
    $pwsh = Resolve-PowerShell7Path
    $arguments = [System.Collections.Generic.List[string]]::new()
    foreach ($item in @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath)) {
        [void]$arguments.Add((ConvertTo-CliArgument $item))
    }
    $forward = @{
        CenterUrl = $CenterUrl; EnrollmentToken = $EnrollmentToken; AgentName = $AgentName
        HostId = $HostId; Version = $Version; ReleaseTag = $ReleaseTag; Mode = $Mode
        DesktopUser = $DesktopUser; BrowserEngine = $BrowserEngine; BrowserName = $BrowserName
        BrowserHeadless = $BrowserHeadless; NodePath = $NodePath; PlaywrightVersion = $PlaywrightVersion
        InstallRoot = $InstallRoot; StateDir = $StateDir
    }
    foreach ($entry in $forward.GetEnumerator()) {
        if ([string]::IsNullOrWhiteSpace([string]$entry.Value) -and $entry.Key -notin @('Mode', 'BrowserEngine', 'BrowserName', 'BrowserHeadless', 'PlaywrightVersion', 'InstallRoot', 'StateDir')) { continue }
        [void]$arguments.Add((ConvertTo-CliArgument ('-{0}' -f $entry.Key)))
        [void]$arguments.Add((ConvertTo-CliArgument ([string]$entry.Value)))
    }
    if ($Desktop) { [void]$arguments.Add('-Desktop') }
    if ($Browser) { [void]$arguments.Add('-Browser') }
    if ($ReEnroll) { [void]$arguments.Add('-ReEnroll') }
    $isAdmin = ([Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
    $start = @{ FilePath = $pwsh; ArgumentList = ($arguments -join ' '); Wait = $true; PassThru = $true; WindowStyle = 'Hidden' }
    if (-not $isAdmin) { $start.Verb = 'RunAs' }
    $child = Start-Process @start
    exit $child.ExitCode
}

# The command may be pasted into Windows PowerShell 5 or a non-elevated
# terminal. Re-enter once in PowerShell 7 and request elevation when needed;
# this is only the installer process, not a resident bootstrap component.
$isAdminNow = ([Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $InternalChild -and ($PSVersionTable.PSVersion.Major -lt 7 -or -not $isAdminNow)) {
    Invoke-ElevatedPowerShell7
}
if ($PSVersionTable.PSVersion.Major -lt 7) { throw 'PowerShell 7 or newer is required.' }

if ([string]::IsNullOrWhiteSpace($HostId)) { $HostId = $AgentName }
if ([string]::IsNullOrWhiteSpace($ReleaseTag)) { $ReleaseTag = "java-$Version" }
if ($CenterUrl.Contains("`r") -or $CenterUrl.Contains("`n")) { throw 'CenterUrl must be one line.' }
$parsedCenterUrl = [Uri]$CenterUrl
if (-not $parsedCenterUrl.IsAbsoluteUri -or $parsedCenterUrl.Scheme -ne 'https') { throw 'CenterUrl must use HTTPS.' }
if ([string]::IsNullOrWhiteSpace($EnrollmentToken)) { throw 'EnrollmentToken is required.' }
if ($Mode -eq 'full') { $Desktop = $true; $Browser = $true }
if ($Desktop -and [string]::IsNullOrWhiteSpace($DesktopUser)) {
    $interactive = (Get-CimInstance Win32_ComputerSystem -ErrorAction SilentlyContinue).UserName
    if (-not [string]::IsNullOrWhiteSpace($interactive)) { $DesktopUser = $interactive.Substring($interactive.LastIndexOf([char]92) + 1) }
    if ([string]::IsNullOrWhiteSpace($DesktopUser)) { throw 'Desktop mode requires -DesktopUser or an active Windows user session.' }
}

$identity = Join-Path $StateDir 'identity.json'
$stage = Join-Path ([IO.Path]::GetTempPath()) ('rcm-first-install-' + [Guid]::NewGuid().ToString('N'))
$runtime = Join-Path $StateDir 'browser-runtime'
$playwrightBrowsersPath = Join-Path $StateDir 'playwright-browsers'
New-Item -ItemType Directory -Path $stage -Force | Out-Null
New-Item -ItemType Directory -Path $StateDir -Force | Out-Null

try {
    $releaseBase = "https://github.com/Prodigalgal/remote_connect_mcp/releases/download/$ReleaseTag"
    $rawBase = "https://raw.githubusercontent.com/Prodigalgal/remote_connect_mcp/$ReleaseTag"
    function Download-Verified {
        param([Parameter(Mandatory = $true)][string]$Asset)
        $destination = Join-Path $stage $Asset
        Invoke-WebRequest -UseBasicParsing -Uri "$releaseBase/$Asset" -OutFile $destination -TimeoutSec 180
        $sidecar = "$destination.sha256"
        Invoke-WebRequest -UseBasicParsing -Uri "$releaseBase/$Asset.sha256" -OutFile $sidecar -TimeoutSec 30
        $parts = ((Get-Content -LiteralPath $sidecar -Raw).Trim() -split '\s+')
        if ($parts.Count -lt 2 -or $parts[0] -notmatch '^[0-9a-fA-F]{64}$' -or ($parts[1].TrimStart('*') -ne $Asset)) { throw "invalid checksum sidecar for $Asset" }
        $actual = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($parts[0].ToLowerInvariant() -ne $actual) { throw "checksum mismatch for $Asset" }
        return $destination
    }

    $arch = 'windows-amd64'
    $agentZip = Download-Verified "remote-connect-mcp-agent-$Version-$arch.zip"
    $desktopZip = $null
    $browserZip = $null
    if ($Desktop) { $desktopZip = Download-Verified "remote-connect-mcp-desktop-$Version-$arch.zip" }
    if ($Browser) { $browserZip = Download-Verified "remote-connect-mcp-browser-$Version-$arch.zip" }
    $installer = Join-Path $stage 'install-java-agent.ps1'
    Invoke-WebRequest -UseBasicParsing -Uri "$rawBase/scripts/install-java-agent.ps1" -OutFile $installer -TimeoutSec 30

    $adapter = ''
    if ($Browser) {
        if ([string]::IsNullOrWhiteSpace($NodePath)) { $NodePath = (Get-Command node.exe -ErrorAction Stop | Select-Object -First 1).Source }
        $NodePath = (Resolve-Path -LiteralPath $NodePath -ErrorAction Stop).Path
        $nodeDirectory = Split-Path -Parent $NodePath
        $npm = @(
            (Get-Command npm.cmd -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -First 1),
            (Get-Command npm.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -First 1),
            (Join-Path $nodeDirectory 'npm.cmd')
        ) | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } | Select-Object -First 1
        if (-not $npm) { throw 'Browser mode requires npm.cmd beside node.exe.' }
        $worker = Join-Path $runtime 'browser-worker.mjs'
        New-Item -ItemType Directory -Path $runtime, $playwrightBrowsersPath -Force | Out-Null
        Invoke-WebRequest -UseBasicParsing -Uri "$rawBase/scripts/browser-worker.mjs" -OutFile $worker -TimeoutSec 30
        $packageJson = Join-Path $runtime 'package.json'
        if (-not (Test-Path -LiteralPath $packageJson)) { Set-Content -LiteralPath $packageJson -Value '{"name":"rcm-browser-runtime","private":true}' -Encoding UTF8 }
        $env:PLAYWRIGHT_BROWSERS_PATH = $playwrightBrowsersPath
        & $npm install --prefix $runtime --no-save --ignore-scripts "playwright@$PlaywrightVersion"
        if ($LASTEXITCODE -ne 0) { throw "npm install failed with exit $LASTEXITCODE" }
        $playwrightCli = Join-Path $runtime 'node_modules\playwright\cli.js'
        if (-not (Test-Path -LiteralPath $playwrightCli -PathType Leaf)) { throw 'Playwright CLI was not installed.' }
        & $NodePath $playwrightCli install $BrowserName
        if ($LASTEXITCODE -ne 0) { throw "Playwright browser install failed with exit $LASTEXITCODE" }
        $adapter = Join-Path $runtime 'browser-adapter.cmd'
        Set-Content -LiteralPath $adapter -Value "@echo off`r`n`"$NodePath`" `"$worker`"`r`n" -Encoding ASCII -Force
    }

    $capabilities = 'command,durable_tasks,file_transfer'
    if ($Desktop) { $capabilities += ',desktop' }
    if ($Browser) { $capabilities += ',browser' }
    $env:REMOTE_CONNECT_MCP_PWSH_PATH = Resolve-PowerShell7Path
    if ($Desktop) { $env:USERNAME = $DesktopUser }
    $arguments = @(
        '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', $installer,
        '-BinaryPath', $agentZip, '-AgentName', $AgentName, '-HostId', $HostId,
        '-CenterUrl', $CenterUrl, '-EnrollmentToken', $EnrollmentToken,
        '-DefaultCwd', 'C:\', '-ScopeMode', 'unrestricted', '-Capabilities', $capabilities,
        '-Version', $Version, '-InstallRoot', $InstallRoot, '-StateDir', $StateDir,
        '-MaxConcurrency', '1', '-MaxBrowserWorkers', '1', '-MaxChildProcesses', '32',
        '-MaxTotalChildProcesses', '32'
    )
    if ($Desktop) { $arguments += @('-DesktopEnabled', '-DesktopBinaryPath', $desktopZip) }
    if ($Browser) { $arguments += @('-BrowserBinaryPath', $browserZip, '-BrowserAdapter', $adapter, '-BrowserEngine', $BrowserEngine, '-BrowserName', $BrowserName, '-BrowserHeadless', $BrowserHeadless, '-PlaywrightBrowsersPath', $playwrightBrowsersPath, '-BrowserProfileDir', (Join-Path $StateDir 'browser-profile')) }
    if ($ReEnroll) { $arguments += '-ReEnroll' }
    & $env:REMOTE_CONNECT_MCP_PWSH_PATH @arguments
    if ($LASTEXITCODE -ne 0) { throw "Java Agent installation failed with exit $LASTEXITCODE" }
    [ordered]@{
        status = 'installed'; agent = $AgentName; version = $Version; capabilities = $capabilities
        desktop = [bool]$Desktop; browser = [bool]$Browser; identity_created = (Test-Path -LiteralPath $identity -PathType Leaf)
        installed_at = (Get-Date).ToUniversalTime().ToString('o')
    } | ConvertTo-Json -Compress
} finally {
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue }
}

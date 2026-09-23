[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$AgentName,
    [string]$HostId = "",
    [string]$CenterUrl = "",
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$ReleaseTag = "",
    [string]$StageRoot = "",
    [string]$InstallRoot = "$env:ProgramFiles\Remote Connect MCP Agent",
    [string]$StateDir = "$env:ProgramData\RemoteConnectMCPAgent",
    [string]$NodePath = "",
    [ValidatePattern("^[A-Za-z_][A-Za-z0-9_.-]{0,63}$")]
    [string]$DesktopUser = "",
    [string]$BrowserProfileDir = "",
    [ValidateSet("0", "1")]
    [string]$BrowserHeadless = "1"
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

if ([string]::IsNullOrWhiteSpace($HostId)) { $HostId = $AgentName }
if ([string]::IsNullOrWhiteSpace($ReleaseTag)) { $ReleaseTag = "java-$Version" }
if ([string]::IsNullOrWhiteSpace($CenterUrl) -or $CenterUrl.Contains("`r") -or $CenterUrl.Contains("`n")) {
    throw "CenterUrl is required and must be one line."
}
$parsedCenterUrl = [Uri]$CenterUrl
if (-not $parsedCenterUrl.IsAbsoluteUri -or $parsedCenterUrl.Scheme -ne 'https') {
    throw "CenterUrl must use HTTPS."
}
if ([string]::IsNullOrWhiteSpace($StageRoot)) {
    $StageRoot = Join-Path $StateDir ("desktop-browser-" + $Version)
}
if ([string]::IsNullOrWhiteSpace($BrowserProfileDir)) {
    $BrowserProfileDir = Join-Path $StateDir "browser-profile"
}
$camoufoxInstallDir = Join-Path $StateDir 'camoufox'
New-Item -ItemType Directory -Path $camoufoxInstallDir -Force | Out-Null

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Run this deployment from an elevated PowerShell or SYSTEM task."
}
$pwsh = Resolve-PowerShell7Path

New-Item -ItemType Directory -Path $StageRoot -Force | Out-Null
$driveName = [IO.Path]::GetPathRoot($StageRoot).TrimEnd('\').TrimEnd(':')
$drive = Get-PSDrive -Name $driveName -ErrorAction Stop
if ($drive.Free -lt 1GB) { throw "free space on $driveName`: is below 1 GiB" }

$releaseBase = "https://github.com/Prodigalgal/remote_connect_mcp/releases/download/$ReleaseTag"
$rawBase = "https://raw.githubusercontent.com/Prodigalgal/remote_connect_mcp/$ReleaseTag"

function Download-Verified {
    param([Parameter(Mandatory = $true)][string]$Asset)
    $url = "$releaseBase/$Asset"
    $zip = Join-Path $StageRoot $Asset
    $shaFile = "$zip.sha256"
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $zip -TimeoutSec 180
    Invoke-WebRequest -UseBasicParsing -Uri "$url.sha256" -OutFile $shaFile -TimeoutSec 30
    $expected = ((Get-Content -LiteralPath $shaFile -Raw).Trim() -split '\s+')[0].ToLowerInvariant()
    $actual = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($expected -ne $actual) { throw "checksum mismatch for $Asset" }
    return $zip
}

$agentZip = Download-Verified "remote-connect-mcp-agent-$Version-windows-amd64.zip"
$desktopZip = Download-Verified "remote-connect-mcp-desktop-$Version-windows-amd64.zip"
$browserZip = Download-Verified "remote-connect-mcp-browser-$Version-windows-amd64.zip"
$installer = Join-Path $StageRoot "install-agent.ps1"
# Prefer the checked-out installer when this script is run from the repository;
# this keeps local recovery aligned with the current PowerShell/MSIX fixes. A
# downloaded tagged copy remains the fallback for standalone deployments.
$localInstaller = Join-Path $PSScriptRoot 'install-agent.ps1'
if (Test-Path -LiteralPath $localInstaller -PathType Leaf) {
    Copy-Item -LiteralPath $localInstaller -Destination $installer -Force
} else {
    Invoke-WebRequest -UseBasicParsing -Uri "$rawBase/scripts/install-agent.ps1" -OutFile $installer -TimeoutSec 30
}

$runtime = Join-Path $StageRoot "browser-runtime"
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$worker = Join-Path $runtime "browser-worker.mjs"
Invoke-WebRequest -UseBasicParsing -Uri "$rawBase/scripts/browser-worker.mjs" -OutFile $worker -TimeoutSec 30
foreach ($name in @('package.json', 'package-lock.json')) {
    Invoke-WebRequest -UseBasicParsing -Uri "$rawBase/scripts/browser-runtime/$name" -OutFile (Join-Path $runtime $name) -TimeoutSec 30
}
$node = if ([string]::IsNullOrWhiteSpace($NodePath)) {
    (Get-Command node.exe -ErrorAction Stop).Source
} else {
    (Resolve-Path -LiteralPath $NodePath -ErrorAction Stop).Path
}
$nodeMajor = [int](& $node -p 'process.versions.node.split(".")[0]')
if ($nodeMajor -lt 22) { throw 'Camoufox requires Node.js 22 or newer.' }
$env:CAMOUFOX_INSTALL_DIR = $camoufoxInstallDir
$nodeDirectory = Split-Path -Parent $node
$npm = @(
    (Join-Path $nodeDirectory "npm.cmd"),
    (Get-Command npm.cmd -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -First 1),
    (Get-Command npm.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -First 1)
) | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } | Select-Object -First 1
if ($npm) {
    & $npm ci --prefix $runtime --no-audit --no-fund
} else {
    $npmCli = Join-Path $nodeDirectory "node_modules\npm\bin\npm-cli.js"
    if (-not (Test-Path -LiteralPath $npmCli -PathType Leaf)) {
        throw "npm.cmd and npm-cli.js were not found beside node.exe"
    }
    & $node $npmCli ci --prefix $runtime --no-audit --no-fund
}
if ($LASTEXITCODE -ne 0) { throw "Camoufox npm install failed with exit $LASTEXITCODE" }
& $node (Join-Path $runtime 'node_modules\camoufox-js\dist\__main__.js') fetch
if ($LASTEXITCODE -ne 0) { throw "Camoufox browser install failed with exit $LASTEXITCODE" }

function ConvertTo-PSLiteral {
    param([AllowEmptyString()][string]$Value)
    return "'" + $Value.Replace("'", "''") + "'"
}

# The current command Agent must not stop itself while the installer replaces
# its loaded Native Image bundle.  A short-lived SYSTEM task applies the
# change after this staging command has returned to Center.
$apply = Join-Path $StageRoot "apply.ps1"
$statusFile = Join-Path $StageRoot "apply-status.json"
$applyTaskName = "RemoteConnectMCPDesktopBrowserApply-" + ($Version -replace '[^A-Za-z0-9]', '')
$applyLines = @(
    '$ErrorActionPreference = "Stop"',
    '$ProgressPreference = "SilentlyContinue"',
    ('$stage = {0}' -f (ConvertTo-PSLiteral $StageRoot)),
    ('$statusFile = {0}' -f (ConvertTo-PSLiteral $statusFile)),
    ('$applyTaskName = {0}' -f (ConvertTo-PSLiteral $applyTaskName)),
    ('$agentName = {0}' -f (ConvertTo-PSLiteral $AgentName)),
    ('$hostId = {0}' -f (ConvertTo-PSLiteral $HostId)),
    ('$centerUrl = {0}' -f (ConvertTo-PSLiteral $CenterUrl)),
    ('$version = {0}' -f (ConvertTo-PSLiteral $Version)),
    ('$installRoot = {0}' -f (ConvertTo-PSLiteral $InstallRoot)),
    ('$stateDir = {0}' -f (ConvertTo-PSLiteral $StateDir)),
    ('$nodePath = {0}' -f (ConvertTo-PSLiteral $node)),
    ('$camoufoxInstallDir = {0}' -f (ConvertTo-PSLiteral $camoufoxInstallDir)),
    ('$desktopUser = {0}' -f (ConvertTo-PSLiteral $DesktopUser)),
    ('$browserProfileDir = {0}' -f (ConvertTo-PSLiteral $BrowserProfileDir)),
    ('$browserHeadless = {0}' -f (ConvertTo-PSLiteral $BrowserHeadless)),
    'try {',
    '  $u = (Get-CimInstance Win32_ComputerSystem).UserName',
    '  if ([string]::IsNullOrWhiteSpace($desktopUser)) { if (-not [string]::IsNullOrWhiteSpace($u)) { $desktopUser = $u.Substring($u.LastIndexOf([char]92) + 1) } }',
    '  if ([string]::IsNullOrWhiteSpace($desktopUser)) { throw "no interactive user; pass -DesktopUser for a not-yet-logged-in GUI host" }',
    '  $env:USERNAME = $desktopUser',
    '  $node = $nodePath',
    ('  $env:REMOTE_CONNECT_MCP_PWSH_PATH = {0}' -f (ConvertTo-PSLiteral $pwsh)),
    '  $worker = Join-Path $stage "browser-runtime\browser-worker.mjs"',
    # Keep the adapter executable invocation in a tiny .cmd shim.  Passing a
    # quoted multi-path command as one ProcessBuilder argument is parsed twice
    # by cmd.exe and fails on Windows when either path contains spaces.  The
    # shim is stable inside the staged runtime directory and retains normal
    # quoted paths for Node and the Worker.
    '  $adapterWrapper = Join-Path $stage "browser-runtime\browser-adapter.cmd"',
    '  $adapterContent = "@echo off`r`n`"$node`" `"$worker`"`r`n"',
    '  Set-Content -LiteralPath $adapterWrapper -Value $adapterContent -Encoding ASCII -Force',
    '  $adapter = $adapterWrapper',
    '  $installer = Join-Path $stage "install-agent.ps1"',
    ('  $agent = Join-Path $stage {0}' -f (ConvertTo-PSLiteral (Split-Path -Leaf $agentZip))),
    ('  $desktop = Join-Path $stage {0}' -f (ConvertTo-PSLiteral (Split-Path -Leaf $desktopZip))),
    ('  $browser = Join-Path $stage {0}' -f (ConvertTo-PSLiteral (Split-Path -Leaf $browserZip))),
    ('  & {0} -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $installer -BinaryPath $agent -AgentName $agentName -HostId $hostId -CenterUrl $centerUrl -DefaultCwd "C:\" -Capabilities "command,durable_tasks,desktop,browser,file_transfer" -Version $version -DesktopEnabled -DesktopBinaryPath $desktop -BrowserBinaryPath $browser -BrowserAdapter $adapter -BrowserEngine camoufox -BrowserName firefox -BrowserHeadless $browserHeadless -CamoufoxInstallDir $camoufoxInstallDir -BrowserProfileDir $browserProfileDir -MaxConcurrency 1 -MaxBrowserWorkers 1 -DesktopMaxLaunchedProcesses 16 -MaxChildProcesses 32 -MaxTotalChildProcesses 32' -f (ConvertTo-PSLiteral $pwsh)),
    '  if ($LASTEXITCODE -ne 0) { throw "agent installer failed with exit $LASTEXITCODE" }',
    '  try { Start-ScheduledTask -TaskName "RemoteConnectMCPDesktopCompanion" -ErrorAction Stop } catch { }',
    '  Start-Sleep -Seconds 3',
    '  $desktopState = (Get-ScheduledTask -TaskName "RemoteConnectMCPDesktopCompanion" -ErrorAction SilentlyContinue).State',
    '  [ordered]@{ status = "completed"; desktop_task_state = [string]$desktopState; browser_runtime = "camoufox"; finished_at = (Get-Date).ToUniversalTime().ToString("o") } | ConvertTo-Json | Set-Content -LiteralPath $statusFile -Encoding UTF8',
    '} catch {',
    '  [ordered]@{ status = "failed"; error = $_.Exception.Message; finished_at = (Get-Date).ToUniversalTime().ToString("o") } | ConvertTo-Json | Set-Content -LiteralPath $statusFile -Encoding UTF8',
    '  exit 1',
    '} finally {',
    '  Unregister-ScheduledTask -TaskName $applyTaskName -Confirm:$false -ErrorAction SilentlyContinue',
    '}'
)
Set-Content -LiteralPath $apply -Value ($applyLines -join [Environment]::NewLine) -Encoding Unicode -Force

$action = New-ScheduledTaskAction -Execute $pwsh -Argument ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "{0}"' -f $apply)
$trigger = New-ScheduledTaskTrigger -Once -At (Get-Date).AddSeconds(15)
$applyPrincipal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
Unregister-ScheduledTask -TaskName $applyTaskName -Confirm:$false -ErrorAction SilentlyContinue
Register-ScheduledTask -TaskName $applyTaskName -Action $action -Trigger $trigger -Principal $applyPrincipal -Description "Remote Connect MCP Desktop/Browser $Version apply" -Force | Out-Null
Start-ScheduledTask -TaskName $applyTaskName

[ordered]@{
    status = "staged"
    scheduled_task = $applyTaskName
    desktop_sha256_verified = $true
    browser_sha256_verified = $true
    browser_runtime = 'camoufox'
    scheduled_at = (Get-Date).ToUniversalTime().ToString("o")
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $StageRoot "stage-status.json") -Encoding UTF8
Write-Output "RCM_DESKTOP_BROWSER_STAGE_OK"

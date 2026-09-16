[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$AgentName,
    [string]$HostId = "",
    [string]$Version = "v0.1.28",
    [string]$ReleaseTag = "",
    [string]$StageRoot = "",
    [string]$InstallRoot = "$env:ProgramFiles\Remote Connect MCP Agent",
    [string]$StateDir = "$env:ProgramData\RemoteConnectMCPAgent",
    [ValidatePattern("^[A-Za-z_][A-Za-z0-9_.-]{0,63}$")]
    [string]$DesktopUser = "",
    [string]$BrowserProfileDir = "",
    [ValidateSet("playwright", "patchright", "comoufox")]
    [string]$BrowserEngine = "playwright",
    [ValidateSet("chromium", "firefox", "webkit")]
    [string]$BrowserName = "chromium",
    [ValidateSet("0", "1")]
    [string]$BrowserHeadless = "1",
    [string]$PlaywrightVersion = "1.63.0"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

if ([string]::IsNullOrWhiteSpace($HostId)) { $HostId = $AgentName }
if ([string]::IsNullOrWhiteSpace($ReleaseTag)) { $ReleaseTag = "java-$Version" }
if ([string]::IsNullOrWhiteSpace($StageRoot)) {
    $StageRoot = Join-Path $StateDir ("desktop-browser-" + $Version)
}
if ([string]::IsNullOrWhiteSpace($BrowserProfileDir)) {
    $BrowserProfileDir = Join-Path $StateDir "browser-profile"
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Run this deployment from an elevated PowerShell or SYSTEM task."
}

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

$desktopZip = Download-Verified "remote-connect-mcp-desktop-$Version-windows-amd64.zip"
$browserZip = Download-Verified "remote-connect-mcp-browser-$Version-windows-amd64.zip"
$installer = Join-Path $StageRoot "install-java-agent.ps1"
$worker = Join-Path $StageRoot "browser-worker.mjs"
Invoke-WebRequest -UseBasicParsing -Uri "$rawBase/scripts/install-java-agent.ps1" -OutFile $installer -TimeoutSec 30
Invoke-WebRequest -UseBasicParsing -Uri "$rawBase/scripts/browser-worker.mjs" -OutFile $worker -TimeoutSec 30

$runtime = Join-Path $StageRoot "browser-runtime"
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$packageJson = Join-Path $runtime "package.json"
if (-not (Test-Path -LiteralPath $packageJson)) {
    Set-Content -LiteralPath $packageJson -Value '{"name":"rcm-browser-runtime","private":true}' -Encoding UTF8
}
$node = (Get-Command node.exe -ErrorAction Stop).Source
$nodeDirectory = Split-Path -Parent $node
$npm = @(
    (Get-Command npm.cmd -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -First 1),
    (Get-Command npm.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -First 1),
    (Join-Path $nodeDirectory "npm.cmd")
) | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } | Select-Object -First 1
if ($npm) {
    & $npm install --prefix $runtime --no-save --ignore-scripts "playwright@$PlaywrightVersion"
} else {
    $npmCli = Join-Path $nodeDirectory "node_modules\npm\bin\npm-cli.js"
    if (-not (Test-Path -LiteralPath $npmCli -PathType Leaf)) {
        throw "npm.cmd and npm-cli.js were not found beside node.exe"
    }
    & $node $npmCli install --prefix $runtime --no-save --ignore-scripts "playwright@$PlaywrightVersion"
}
if ($LASTEXITCODE -ne 0) { throw "npm install failed with exit $LASTEXITCODE" }
$playwrightCli = Join-Path $runtime "node_modules\playwright\cli.js"
if (-not (Test-Path -LiteralPath $playwrightCli)) { throw "Playwright CLI was not installed" }
& $node $playwrightCli install chromium
if ($LASTEXITCODE -ne 0) { throw "Playwright Chromium install failed with exit $LASTEXITCODE" }

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
    ('$version = {0}' -f (ConvertTo-PSLiteral $Version)),
    ('$installRoot = {0}' -f (ConvertTo-PSLiteral $InstallRoot)),
    ('$stateDir = {0}' -f (ConvertTo-PSLiteral $StateDir)),
    ('$desktopUser = {0}' -f (ConvertTo-PSLiteral $DesktopUser)),
    ('$browserProfileDir = {0}' -f (ConvertTo-PSLiteral $BrowserProfileDir)),
    ('$browserEngine = {0}' -f (ConvertTo-PSLiteral $BrowserEngine)),
    ('$browserName = {0}' -f (ConvertTo-PSLiteral $BrowserName)),
    ('$browserHeadless = {0}' -f (ConvertTo-PSLiteral $BrowserHeadless)),
    'try {',
    '  $u = (Get-CimInstance Win32_ComputerSystem).UserName',
    '  if ([string]::IsNullOrWhiteSpace($desktopUser)) { if (-not [string]::IsNullOrWhiteSpace($u)) { $desktopUser = $u.Substring($u.LastIndexOf([char]92) + 1) } }',
    '  if ([string]::IsNullOrWhiteSpace($desktopUser)) { throw "no interactive user; pass -DesktopUser for a not-yet-logged-in GUI host" }',
    '  $env:USERNAME = $desktopUser',
    '  $node = (Get-Command node.exe -ErrorAction Stop).Source',
    '  $worker = Join-Path $stage "browser-runtime\browser-worker.mjs"',
    '  $adapter = ''"{0}" "{1}"'' -f $node, $worker',
    '  $installer = Join-Path $stage "install-java-agent.ps1"',
    '  $binary = Join-Path $installRoot "rcm-agent.exe"',
    ('  $desktop = Join-Path $stage {0}' -f (ConvertTo-PSLiteral (Split-Path -Leaf $desktopZip))),
    ('  $browser = Join-Path $stage {0}' -f (ConvertTo-PSLiteral (Split-Path -Leaf $browserZip))),
    '  & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $installer -BinaryPath $binary -AgentName $agentName -HostId $hostId -DefaultCwd "C:\" -ScopeMode unrestricted -Capabilities "command,durable_tasks,desktop,browser" -Version $version -DesktopEnabled -DesktopBinaryPath $desktop -BrowserBinaryPath $browser -BrowserAdapter $adapter -BrowserEngine $browserEngine -BrowserName $browserName -BrowserHeadless $browserHeadless -BrowserProfileDir $browserProfileDir -MaxConcurrency 1 -MaxBrowserWorkers 1 -DesktopMaxLaunchedProcesses 16 -MaxChildProcesses 32 -MaxTotalChildProcesses 32',
    '  if ($LASTEXITCODE -ne 0) { throw "agent installer failed with exit $LASTEXITCODE" }',
    '  try { Start-ScheduledTask -TaskName "RemoteConnectMCPDesktopCompanion" -ErrorAction Stop } catch { }',
    '  Start-Sleep -Seconds 3',
    '  $desktopState = (Get-ScheduledTask -TaskName "RemoteConnectMCPDesktopCompanion" -ErrorAction SilentlyContinue).State',
    '  [ordered]@{ status = "completed"; desktop_task_state = [string]$desktopState; browser_runtime = "playwright-' + $PlaywrightVersion + '"; finished_at = (Get-Date).ToUniversalTime().ToString("o") } | ConvertTo-Json | Set-Content -LiteralPath $statusFile -Encoding UTF8',
    '} catch {',
    '  [ordered]@{ status = "failed"; error = $_.Exception.Message; finished_at = (Get-Date).ToUniversalTime().ToString("o") } | ConvertTo-Json | Set-Content -LiteralPath $statusFile -Encoding UTF8',
    '  exit 1',
    '} finally {',
    '  Unregister-ScheduledTask -TaskName $applyTaskName -Confirm:$false -ErrorAction SilentlyContinue',
    '}'
)
Set-Content -LiteralPath $apply -Value ($applyLines -join [Environment]::NewLine) -Encoding Unicode -Force

$action = New-ScheduledTaskAction -Execute "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -Argument ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "{0}"' -f $apply)
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
    browser_runtime = "playwright-$PlaywrightVersion"
    scheduled_at = (Get-Date).ToUniversalTime().ToString("o")
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $StageRoot "stage-status.json") -Encoding UTF8
Write-Output "RCM_DESKTOP_BROWSER_STAGE_OK"

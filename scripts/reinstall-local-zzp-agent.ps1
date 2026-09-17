[CmdletBinding()]
param(
    [string]$CenterEnvPath = 'D:\WorkSpace\Project\服务器管理\private\remote-connect-mcp\center.env',
    [string]$CenterUrl = 'https://remote-connect-mcp-center.fantong.eu.org',
    [string]$AgentCenterUrl = 'https://remote-connect-mcp-agent.fantong.eu.org',
    [string]$Release = 'v0.0.0-main.80',
    [string]$AgentName = 'local-zzp-laptop-windows',
    [string]$HostId = '',
    [string]$DefaultCwd = 'C:\',
    [string]$InstallRoot = "$env:ProgramFiles\Remote Connect MCP Agent",
    [string]$StateDir = "$env:ProgramData\RemoteConnectMCPAgent",
    [string]$ExpectedSha256 = '3ed8e97fd8b87c4ecd6a58d853a009b79366ccdd460f003debb9dc72c3e49a56'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from an elevated PowerShell window.'
}

$installer = Join-Path $PSScriptRoot 'install-java-agent.ps1'
if (-not (Test-Path -LiteralPath $installer -PathType Leaf)) {
    throw "Installer not found: $installer"
}
if (-not (Test-Path -LiteralPath $CenterEnvPath -PathType Leaf)) {
    throw "Center env file not found: $CenterEnvPath"
}

$cfg = ConvertFrom-StringData (Get-Content -LiteralPath $CenterEnvPath -Raw)
$admin = [string]$cfg['REMOTE_CONNECT_MCP_CENTER_ADMIN_TOKEN']
if ([string]::IsNullOrWhiteSpace($admin)) {
    throw 'Center Admin Token not found or empty.'
}
$headers = @{ Authorization = "Bearer $admin" }

# Validate credentials before making any local changes.
try {
    $machineSnapshot = Invoke-RestMethod "$CenterUrl/api/v1/admin/machines?offset=0&limit=100" -Headers $headers
} catch {
    throw 'Center Admin Token was rejected; local Agent was not modified.'
}

# Re-enrollment must preserve the immutable host_id already associated with a
# machine name.  Supplying a different host_id is intentionally rejected by
# Center as a same-name conflict.  For a new name, the name itself is the
# stable default.
$existingMachine = @($machineSnapshot.items) |
    Where-Object { $_.name -eq $AgentName } |
    Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($HostId)) {
    $HostId = if ($existingMachine) { [string]$existingMachine.host_id } else { $AgentName }
}
if ($existingMachine -and -not [string]::Equals([string]$existingMachine.host_id, $HostId, [StringComparison]::Ordinal)) {
    throw "HostId '$HostId' does not match the existing Center identity for '$AgentName'."
}

$zip = Join-Path $env:TEMP "remote-connect-mcp-agent-$Release-windows-amd64.zip"
$assetUrl = "https://github.com/Prodigalgal/remote_connect_mcp/releases/download/java-$Release/remote-connect-mcp-agent-$Release-windows-amd64.zip"
if (Test-Path -LiteralPath $zip) {
    $existingHash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($existingHash -ne $ExpectedSha256.ToLowerInvariant()) {
        Remove-Item -LiteralPath $zip -Force
    }
}
if (-not (Test-Path -LiteralPath $zip)) {
    Invoke-WebRequest -Uri $assetUrl -OutFile $zip -UseBasicParsing
}
$actualHash = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualHash -ne $ExpectedSha256.ToLowerInvariant()) {
    Remove-Item -LiteralPath $zip -Force
    throw "Release SHA-256 mismatch: $actualHash"
}

# Issue the one-time token only after the release is ready. It is kept in
# memory and passed directly to the installer; it is never written to disk.
$issueBody = @{
    requested_name = $AgentName
    expires_in_seconds = 3600
} | ConvertTo-Json -Compress
$issued = Invoke-RestMethod -Method Post `
    -Uri "$CenterUrl/api/v1/admin/enrollment-tokens" `
    -Headers $headers `
    -ContentType 'application/json' `
    -Body $issueBody
$enrollment = [string]$issued.token
if ([string]::IsNullOrWhiteSpace($enrollment)) {
    throw 'Center did not return an enrollment token.'
}

try {
    foreach ($name in @('rcm-agent.exe', 'remote-connect-mcp-agent.exe')) {
        @(Get-CimInstance Win32_Process -Filter "Name='$name'" -ErrorAction SilentlyContinue) |
            ForEach-Object { Stop-Process -Id ([int]$_.ProcessId) -Force -ErrorAction SilentlyContinue }
    }
    Start-Sleep -Seconds 2

    & $installer -Uninstall -PurgeState -InstallRoot $InstallRoot -StateDir $StateDir
    if (Test-Path -LiteralPath (Join-Path $StateDir 'identity.json')) {
        throw 'Old identity.json still exists after purge; refusing to continue.'
    }

    $installArgs = @{
        BinaryPath = $zip
        CenterUrl = $AgentCenterUrl
        AgentName = $AgentName
        HostId = $HostId
        DefaultCwd = $DefaultCwd
        ScopeMode = 'unrestricted'
        Capabilities = 'command,durable_tasks'
        Version = $Release
        MaxConcurrency = 2
        MaxBrowserWorkers = 1
        ReEnroll = $true
        EnrollmentToken = $enrollment
        InstallRoot = $InstallRoot
        StateDir = $StateDir
    }
    & $installer @installArgs
} finally {
    Remove-Item -LiteralPath $zip -Force -ErrorAction SilentlyContinue
    $issued = $null
    $enrollment = $null
    $admin = $null
    $headers = $null
    $issueBody = $null
    $cfg = $null
}

# Confirm that the new identity is actually heartbeating at Center.
$verifyCfg = ConvertFrom-StringData (Get-Content -LiteralPath $CenterEnvPath -Raw)
$verifyHeaders = @{ Authorization = "Bearer $($verifyCfg['REMOTE_CONNECT_MCP_CENTER_ADMIN_TOKEN'])" }
$deadline = (Get-Date).AddSeconds(90)
$row = $null
do {
    try {
        $machines = Invoke-RestMethod "$CenterUrl/api/v1/admin/machines?offset=0&limit=100" -Headers $verifyHeaders
        $row = @($machines.items) | Where-Object { $_.name -eq $AgentName }
    } catch {
        $row = $null
    }
    if ($row -and ([bool]$row.online)) { break }
    Start-Sleep -Seconds 5
} while ((Get-Date) -lt $deadline)

if (-not ($row -and ([bool]$row.online))) {
    throw "Agent installed but Center heartbeat is offline. last_seen=$($row.last_seen)"
}

$row | Select-Object name, version, last_seen, online | Format-List
Get-ScheduledTask -TaskName 'RemoteConnectMCPAgent' |
    Select-Object TaskName, State | Format-List

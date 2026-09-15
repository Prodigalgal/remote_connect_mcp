[CmdletBinding()]
param(
    [switch]$Uninstall,
    [switch]$PurgeState,
    [string]$BinaryPath,
	[string]$CenterUrl = "https://agent.example.invalid",
    [string]$EnrollmentToken = $env:REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN,
    [string]$AgentName = $env:COMPUTERNAME,
    [string]$HostId = "",
    [string]$DefaultCwd = "C:\",
    # The legacy Go Agent only understands these two persisted modes.  The
    # Java Agent installer exposes the richer project/worktree/path contract.
    [ValidateSet("unrestricted", "workspace")]
    [string]$ScopeMode = "workspace",
    [string]$WorkspaceRoot = "",
    [string]$Capabilities = "command,durable_tasks",
    [string]$Version = "dev",
    [string]$BrowserAdapter = "",
    [switch]$DesktopEnabled,
    [ValidateRange(1, 32)]
    [int]$MaxConcurrency = 1,
    [ValidateRange(1048576, 1073741824)]
    [long]$MaxOutputBytes = 67108864,
    [long]$MaxAggregateOutputBytes = 0,
    [string]$InstallRoot = "$env:ProgramFiles\Remote Connect MCP Agent",
    [string]$StateDir = "$env:ProgramData\RemoteConnectMCPAgent"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$serviceName = "RemoteConnectMCPAgent"
$principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Run this script from an elevated PowerShell 7 window."
}

if ($Uninstall) {
    $service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
    if ($service) {
        if ($service.Status -ne "Stopped") {
            Stop-Service -Name $serviceName -Force
        }
        & sc.exe delete $serviceName | Out-Null
        Start-Sleep -Milliseconds 500
    }
    if (Test-Path -LiteralPath $InstallRoot) {
        Remove-Item -LiteralPath $InstallRoot -Recurse -Force
    }
    if ($PurgeState -and (Test-Path -LiteralPath $StateDir)) {
        Remove-Item -LiteralPath $StateDir -Recurse -Force
    }
    Write-Host "Remote Connect MCP Agent service removed."
    return
}

if ([string]::IsNullOrWhiteSpace($BinaryPath) -or -not (Test-Path -LiteralPath $BinaryPath -PathType Leaf)) {
    throw "BinaryPath must point to remote-connect-mcp-agent.exe."
}
if ([string]::IsNullOrWhiteSpace($EnrollmentToken)) {
    throw "EnrollmentToken is required."
}
if ($EnrollmentToken.Contains("`r") -or $EnrollmentToken.Contains("`n")) {
    throw "EnrollmentToken must be one line."
}
if ($Capabilities.Contains("`r") -or $Capabilities.Contains("`n")) {
    throw "Capabilities must be one line."
}
if ($Version.Contains("`r") -or $Version.Contains("`n") -or [string]::IsNullOrWhiteSpace($Version)) {
    throw "Version must be a non-empty one-line value."
}
if ($BrowserAdapter.Contains("`r") -or $BrowserAdapter.Contains("`n")) {
    throw "BrowserAdapter must be one line."
}
if ([string]::IsNullOrWhiteSpace($AgentName)) {
    throw "AgentName is required."
}
if ($HostId.Contains("`r") -or $HostId.Contains("`n")) {
    throw "HostId must be one line."
}
if (-not (Test-Path -LiteralPath $DefaultCwd -PathType Container)) {
    throw "DefaultCwd does not exist: $DefaultCwd"
}
if ($ScopeMode -eq "workspace") {
    if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
        $WorkspaceRoot = $DefaultCwd
    }
    if (-not (Test-Path -LiteralPath $WorkspaceRoot -PathType Container)) {
        throw "WorkspaceRoot does not exist: $WorkspaceRoot"
    }
}

if ($MaxAggregateOutputBytes -eq 0) {
    $product = [decimal]$MaxOutputBytes * [decimal]$MaxConcurrency
    $MaxAggregateOutputBytes = [long][Math]::Max([decimal]$MaxOutputBytes, [Math]::Min([decimal]268435456, $product))
}
if ($MaxAggregateOutputBytes -lt $MaxOutputBytes -or $MaxAggregateOutputBytes -lt 1048576 -or $MaxAggregateOutputBytes -gt 4294967296) {
    throw "MaxAggregateOutputBytes must be between MaxOutputBytes and 4 GiB."
}

$source = (Resolve-Path -LiteralPath $BinaryPath).Path
$destination = Join-Path $InstallRoot "remote-connect-mcp-agent.exe"
New-Item -ItemType Directory -Path $InstallRoot -Force | Out-Null
New-Item -ItemType Directory -Path $StateDir -Force | Out-Null

$service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
if ($service -and $service.Status -ne "Stopped") {
    Stop-Service -Name $serviceName -Force
    $service.WaitForStatus("Stopped", [TimeSpan]::FromSeconds(30))
}
Copy-Item -LiteralPath $source -Destination $destination -Force

$quotedBinary = '"{0}"' -f $destination
if (-not $service) {
    New-Service -Name $serviceName -BinaryPathName $quotedBinary -DisplayName "Remote Connect MCP Agent" -Description "Outbound agent for the Remote Connect MCP control plane" -StartupType Automatic | Out-Null
} else {
    & sc.exe config $serviceName binPath= $quotedBinary start= auto | Out-Null
}

$serviceRegistry = "HKLM:\SYSTEM\CurrentControlSet\Services\$serviceName"
$environment = [string[]]@(
    "REMOTE_CONNECT_MCP_AGENT_CENTER_URL=$($CenterUrl.TrimEnd('/'))",
    "REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN=$($EnrollmentToken.Trim())",
    "REMOTE_CONNECT_MCP_AGENT_NAME=$($AgentName.Trim())",
    "REMOTE_CONNECT_MCP_AGENT_HOST_ID=$($HostId.Trim())",
    "REMOTE_CONNECT_MCP_AGENT_DEFAULT_CWD=$DefaultCwd",
    "REMOTE_CONNECT_MCP_AGENT_SCOPE_MODE=$ScopeMode",
    "REMOTE_CONNECT_MCP_AGENT_WORKSPACE_ROOT=$WorkspaceRoot",
    "REMOTE_CONNECT_MCP_AGENT_CAPABILITIES=$Capabilities",
    "REMOTE_CONNECT_MCP_AGENT_VERSION=$($Version.Trim())",
    "REMOTE_CONNECT_MCP_AGENT_BROWSER_ADAPTER=$($BrowserAdapter.Trim())",
    "REMOTE_CONNECT_MCP_AGENT_DESKTOP_ENABLED=$($DesktopEnabled.IsPresent.ToString().ToLowerInvariant())",
    "REMOTE_CONNECT_MCP_AGENT_STATE_DIR=$StateDir",
    "REMOTE_CONNECT_MCP_AGENT_LOG_FILE=$(Join-Path $StateDir 'agent.log')",
    "REMOTE_CONNECT_MCP_AGENT_MAX_CONCURRENCY=$MaxConcurrency",
    "REMOTE_CONNECT_MCP_AGENT_MAX_OUTPUT_BYTES=$MaxOutputBytes",
    "REMOTE_CONNECT_MCP_AGENT_MAX_AGGREGATE_OUTPUT_BYTES=$MaxAggregateOutputBytes",
    "REMOTE_CONNECT_MCP_AGENT_BINARY_PATH=$destination",
    "REMOTE_CONNECT_MCP_AGENT_SERVICE_NAME=$serviceName"
)
New-ItemProperty -Path $serviceRegistry -Name Environment -PropertyType MultiString -Value $environment -Force | Out-Null

& sc.exe description $serviceName "Outbound agent for the Remote Connect MCP control plane" | Out-Null
& sc.exe failure $serviceName reset= 86400 actions= restart/5000/restart/15000/restart/30000 | Out-Null
& sc.exe failureflag $serviceName 1 | Out-Null
& icacls.exe $StateDir /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null

Start-Service -Name $serviceName
(Get-Service -Name $serviceName).WaitForStatus("Running", [TimeSpan]::FromSeconds(30))
$installed = Get-Service -Name $serviceName
[pscustomobject]@{
    Service = $installed.Name
    Status = $installed.Status
    StartType = $installed.StartType
    AgentName = $AgentName
    ScopeMode = $ScopeMode
    WorkspaceRoot = $WorkspaceRoot
    MaxOutputBytes = $MaxOutputBytes
    MaxAggregateOutputBytes = $MaxAggregateOutputBytes
    Binary = $destination
    StateDir = $StateDir
    LogFile = Join-Path $StateDir "agent.log"
}

[CmdletBinding()]
param(
    [switch]$Uninstall,
    [switch]$PurgeState,
    [string]$BinaryPath,
	[string]$CenterUrl = "https://agent.example.invalid",
    [string]$EnrollmentToken = $env:REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN,
    [string]$AgentName = $env:COMPUTERNAME,
    [string]$DefaultCwd = "C:\",
    [ValidateRange(1, 32)]
    [int]$MaxConcurrency = 1,
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
if ([string]::IsNullOrWhiteSpace($AgentName)) {
    throw "AgentName is required."
}
if (-not (Test-Path -LiteralPath $DefaultCwd -PathType Container)) {
    throw "DefaultCwd does not exist: $DefaultCwd"
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
    "REMOTE_CONNECT_MCP_AGENT_DEFAULT_CWD=$DefaultCwd",
    "REMOTE_CONNECT_MCP_AGENT_STATE_DIR=$StateDir",
    "REMOTE_CONNECT_MCP_AGENT_LOG_FILE=$(Join-Path $StateDir 'agent.log')",
    "REMOTE_CONNECT_MCP_AGENT_MAX_CONCURRENCY=$MaxConcurrency"
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
    Binary = $destination
    StateDir = $StateDir
    LogFile = Join-Path $StateDir "agent.log"
}

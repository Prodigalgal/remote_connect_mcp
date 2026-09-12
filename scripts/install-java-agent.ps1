param(
    [switch]$Uninstall,
    [switch]$PurgeState,
    [switch]$ReEnroll,
    [string]$BinaryPath,
    [string]$CenterUrl = "https://remote-connect-mcp-agent.example.invalid",
    [string]$EnrollmentToken = $env:REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN,
    [string]$AgentName = $env:COMPUTERNAME,
    [string]$HostId = "",
    [string]$DefaultCwd = "C:\",
    [ValidateSet("unrestricted", "workspace")]
    [string]$ScopeMode = "unrestricted",
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
$companionTaskName = "RemoteConnectMCPDesktopCompanion"

$principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Run this script from an elevated PowerShell 7 window."
}

if ($Uninstall) {
    Unregister-ScheduledTask -TaskName $companionTaskName -Confirm:$false -ErrorAction SilentlyContinue
    $service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
    if ($service) {
        if ($service.Status -ne "Stopped") { Stop-Service -Name $serviceName -Force }
        & sc.exe delete $serviceName | Out-Null
        Start-Sleep -Milliseconds 500
    }
    if (Test-Path -LiteralPath $InstallRoot) { Remove-Item -LiteralPath $InstallRoot -Recurse -Force }
    if ($PurgeState -and (Test-Path -LiteralPath $StateDir)) { Remove-Item -LiteralPath $StateDir -Recurse -Force }
    Write-Host "Remote Connect MCP Java Agent service removed."
    return
}

if ([string]::IsNullOrWhiteSpace($BinaryPath) -or -not (Test-Path -LiteralPath $BinaryPath -PathType Leaf)) {
    throw "BinaryPath must point to rcm-agent.exe or a Windows Native Image Agent ZIP."
}
if ([string]::IsNullOrWhiteSpace($CenterUrl) -or $CenterUrl.Contains("`r") -or $CenterUrl.Contains("`n")) { throw "CenterUrl is required and must be one line." }
$parsedCenterUrl = [Uri]$CenterUrl
if (-not $parsedCenterUrl.IsAbsoluteUri -or $parsedCenterUrl.Scheme -ne 'https') { throw "CenterUrl must use HTTPS." }
if ([string]::IsNullOrWhiteSpace($AgentName)) { throw "AgentName is required." }
if ([string]::IsNullOrWhiteSpace($HostId)) { $HostId = $AgentName }
if (-not (Test-Path -LiteralPath $DefaultCwd -PathType Container)) { throw "DefaultCwd does not exist: $DefaultCwd" }
if ($ScopeMode -eq "workspace") {
    if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) { $WorkspaceRoot = $DefaultCwd }
    if (-not (Test-Path -LiteralPath $WorkspaceRoot -PathType Container)) { throw "WorkspaceRoot does not exist: $WorkspaceRoot" }
}
if ($MaxAggregateOutputBytes -eq 0) {
    $product = [decimal]$MaxOutputBytes * [decimal]$MaxConcurrency
    $MaxAggregateOutputBytes = [long][Math]::Max([decimal]$MaxOutputBytes, [Math]::Min([decimal]268435456, $product))
}
if ($MaxAggregateOutputBytes -lt $MaxOutputBytes -or $MaxAggregateOutputBytes -gt 4294967296) {
    throw "MaxAggregateOutputBytes must be between MaxOutputBytes and 4 GiB."
}

$destination = Join-Path $InstallRoot "rcm-agent.exe"

# A Windows Native Image executable is not self-contained: the GraalVM runtime
# DLLs generated beside it (java.dll, jvm.dll, awt.dll, ...) must stay beside
# the exe. Accept both a raw executable (and its sibling DLLs) and the flat
# release ZIP produced by build-native.ps1/GitHub Actions. Only exe/dll files
# from the selected bundle are copied; checksum/readme metadata never enters
# the service directory.
$inputPath = (Resolve-Path -LiteralPath $BinaryPath).Path
$staging = $null
$sourceFiles = @()
try {
    if ([IO.Path]::GetExtension($inputPath) -ieq '.zip') {
        $staging = Join-Path ([IO.Path]::GetTempPath()) ("rcm-agent-install-" + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $staging -Force | Out-Null
        Expand-Archive -LiteralPath $inputPath -DestinationPath $staging -Force
        $candidates = @(Get-ChildItem -LiteralPath $staging -Filter 'rcm-agent.exe' -File -Recurse)
        if ($candidates.Count -ne 1) {
            throw "Agent ZIP must contain exactly one rcm-agent.exe at a bundle root."
        }
        $sourceRoot = $candidates[0].Directory.FullName
        $sourceFiles = @(Get-ChildItem -LiteralPath $sourceRoot -File |
            Where-Object { $_.Extension -ieq '.exe' -or $_.Extension -ieq '.dll' })
    } else {
        $source = $inputPath
        if ([IO.Path]::GetFileName($source) -ine 'rcm-agent.exe') {
            throw "BinaryPath must point to rcm-agent.exe or a Windows Native Image Agent ZIP."
        }
        $sourceRoot = Split-Path -Parent $source
        $sourceFiles = @(
            Get-Item -LiteralPath $source
            Get-ChildItem -LiteralPath $sourceRoot -Filter '*.dll' -File -ErrorAction SilentlyContinue
        )
    }
    if (-not ($sourceFiles | Where-Object { $_.Name -ieq 'rcm-agent.exe' })) {
        throw "The selected Agent bundle does not contain rcm-agent.exe."
    }
    $sourceFiles = @($sourceFiles | Sort-Object Name -Unique)

    # Stop before replacing a loaded executable/DLL. The service is started
    # again only after registration and the complete bundle are in place.
    $existingService = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
    if ($existingService -and $existingService.Status -ne 'Stopped') {
        Stop-Service -Name $serviceName -Force
        $existingService.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(30))
    }

    New-Item -ItemType Directory -Path $InstallRoot -Force | Out-Null
    New-Item -ItemType Directory -Path $StateDir -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $StateDir 'desktop') -Force | Out-Null
    foreach ($oldDll in @(Get-ChildItem -LiteralPath $InstallRoot -Filter '*.dll' -File -ErrorAction SilentlyContinue)) {
        if (-not ($sourceFiles | Where-Object { $_.Name -ieq $oldDll.Name })) {
            Remove-Item -LiteralPath $oldDll.FullName -Force
        }
    }
    foreach ($file in $sourceFiles) {
        Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $InstallRoot $file.Name) -Force
    }
} finally {
    if ($staging -and (Test-Path -LiteralPath $staging)) {
        Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue
    }
}

$identity = Join-Path $StateDir 'identity.json'
if ($ReEnroll -or -not (Test-Path -LiteralPath $identity -PathType Leaf)) {
    if ([string]::IsNullOrWhiteSpace($EnrollmentToken)) {
        throw "EnrollmentToken is required for first registration or -ReEnroll. It is not stored after registration."
    }
    $bootstrap = @{
        REMOTE_CONNECT_MCP_AGENT_CENTER_URL = $CenterUrl.TrimEnd('/')
        REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN = $EnrollmentToken.Trim()
        REMOTE_CONNECT_MCP_AGENT_NAME = $AgentName.Trim()
        REMOTE_CONNECT_MCP_AGENT_HOST_ID = $HostId.Trim()
        REMOTE_CONNECT_MCP_AGENT_DEFAULT_CWD = $DefaultCwd
        REMOTE_CONNECT_MCP_AGENT_SCOPE_MODE = $ScopeMode
        REMOTE_CONNECT_MCP_AGENT_WORKSPACE_ROOT = $WorkspaceRoot
        REMOTE_CONNECT_MCP_AGENT_CAPABILITIES = $Capabilities
        REMOTE_CONNECT_MCP_AGENT_VERSION = $Version.Trim()
        REMOTE_CONNECT_MCP_AGENT_BROWSER_ADAPTER = $BrowserAdapter.Trim()
        REMOTE_CONNECT_MCP_AGENT_DESKTOP_ENABLED = $DesktopEnabled.IsPresent.ToString().ToLowerInvariant()
        REMOTE_CONNECT_MCP_AGENT_STATE_DIR = $StateDir
        REMOTE_CONNECT_MCP_AGENT_MAX_CONCURRENCY = $MaxConcurrency.ToString()
        REMOTE_CONNECT_MCP_AGENT_MAX_OUTPUT_BYTES = $MaxOutputBytes.ToString()
        REMOTE_CONNECT_MCP_AGENT_MAX_AGGREGATE_OUTPUT_BYTES = $MaxAggregateOutputBytes.ToString()
    }
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $destination
    $psi.ArgumentList.Add('--register-once')
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    foreach ($entry in $bootstrap.GetEnumerator()) { $psi.Environment[$entry.Key] = [string]$entry.Value }
    $registration = [System.Diagnostics.Process]::new()
    $registration.StartInfo = $psi
    if (-not $registration.Start()) { throw "could not start Java Agent registration" }
    $stdout = $registration.StandardOutput.ReadToEndAsync()
    $stderr = $registration.StandardError.ReadToEndAsync()
    $registration.WaitForExit()
    if ($registration.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $identity -PathType Leaf)) {
        $detail = if ($stderr.Result) { $stderr.Result.Trim() } else { $stdout.Result.Trim() }
        throw "Java Agent registration failed (exit $($registration.ExitCode)): $detail"
    }
    $registration.Dispose()
}

$service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
$quotedBinary = '"{0}"' -f $destination
if (-not $service) {
    New-Service -Name $serviceName -BinaryPathName $quotedBinary -DisplayName "Remote Connect MCP Java Agent" -Description "Java Native Image Agent for the Remote Connect MCP control plane" -StartupType Automatic | Out-Null
} else {
    & sc.exe config $serviceName binPath= $quotedBinary start= auto | Out-Null
}

$serviceRegistry = "HKLM:\SYSTEM\CurrentControlSet\Services\$serviceName"
$environment = [string[]]@(
    "REMOTE_CONNECT_MCP_AGENT_CENTER_URL=$($CenterUrl.TrimEnd('/'))",
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
    "REMOTE_CONNECT_MCP_AGENT_MAX_CONCURRENCY=$MaxConcurrency",
    "REMOTE_CONNECT_MCP_AGENT_MAX_OUTPUT_BYTES=$MaxOutputBytes",
    "REMOTE_CONNECT_MCP_AGENT_MAX_AGGREGATE_OUTPUT_BYTES=$MaxAggregateOutputBytes",
    "REMOTE_CONNECT_MCP_AGENT_BINARY_PATH=$destination",
    "REMOTE_CONNECT_MCP_AGENT_SERVICE_NAME=$serviceName"
)
New-ItemProperty -Path $serviceRegistry -Name Environment -PropertyType MultiString -Value $environment -Force | Out-Null
& sc.exe description $serviceName "Java Native Image Agent for the Remote Connect MCP control plane" | Out-Null
& sc.exe failure $serviceName reset= 86400 actions= restart/5000/restart/15000/restart/30000 | Out-Null
& sc.exe failureflag $serviceName 1 | Out-Null
& icacls.exe $StateDir /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
if ($DesktopEnabled) {
    # The SCM service remains the single Center identity. A per-logon task
    # only hosts the same binary in the interactive session so AWT/User32 can
    # see the desktop; it communicates through the protected state/desktop
    # loopback endpoint and never registers another Agent.
    $desktopDir = Join-Path $StateDir 'desktop'
    & icacls.exe $desktopDir /grant:r "$env:USERNAME:(OI)(CI)M" | Out-Null
    # Scheduled tasks do not inherit the SCM service's registry Environment
    # block. Pass the resolved state directory explicitly so a custom
    # ProgramData path still points at the same protected IPC endpoint.
    $escapedStateDir = $StateDir.Replace('"', '\\"')
    $action = New-ScheduledTaskAction -Execute $destination -Argument ('--desktop-companion "{0}"' -f $escapedStateDir)
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
    $principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType InteractiveToken -RunLevel Limited
    Register-ScheduledTask -TaskName $companionTaskName -Action $action -Trigger $trigger -Principal $principal -Description "Interactive desktop companion for Remote Connect MCP" -Force | Out-Null
} else {
    Unregister-ScheduledTask -TaskName $companionTaskName -Confirm:$false -ErrorAction SilentlyContinue
}

Start-Service -Name $serviceName
(Get-Service -Name $serviceName).WaitForStatus("Running", [TimeSpan]::FromSeconds(30))
[pscustomobject]@{
    Service = $serviceName
    Status = (Get-Service -Name $serviceName).Status
    AgentName = $AgentName
    EnrollmentTokenStoredInService = $false
    Binary = $destination
    StateDir = $StateDir
    MaxConcurrency = $MaxConcurrency
    MaxAggregateOutputBytes = $MaxAggregateOutputBytes
}

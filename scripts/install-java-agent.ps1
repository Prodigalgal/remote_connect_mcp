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
        [ValidateSet("unrestricted", "project", "worktree", "path", "workspace")]
        [string]$ScopeMode = "workspace",
    [string]$WorkspaceRoot = "",
    [string]$Capabilities = "command,durable_tasks",
    [string]$Version = "dev",
    [string]$BrowserAdapter = "",
    [string]$BrowserProfileDir = $env:REMOTE_CONNECT_MCP_AGENT_BROWSER_PROFILE_DIR,
    [ValidateSet("playwright", "patchright", "comoufox")]
    [string]$BrowserEngine = $(if ($env:REMOTE_CONNECT_MCP_AGENT_BROWSER_ENGINE) { $env:REMOTE_CONNECT_MCP_AGENT_BROWSER_ENGINE } else { "playwright" }),
    [ValidateSet("chromium", "firefox", "webkit")]
    [string]$BrowserName = $(if ($env:REMOTE_CONNECT_MCP_AGENT_BROWSER) { $env:REMOTE_CONNECT_MCP_AGENT_BROWSER } else { "chromium" }),
    [ValidateSet("0", "1")]
    [string]$BrowserHeadless = $(if ($env:REMOTE_CONNECT_MCP_AGENT_BROWSER_HEADLESS) { $env:REMOTE_CONNECT_MCP_AGENT_BROWSER_HEADLESS } else { "1" }),
    [string]$DesktopBinaryPath = "",
    [string]$BrowserBinaryPath = "",
    [switch]$DesktopEnabled,
    [ValidateRange(1, 32)]
    [int]$MaxConcurrency = 1,
    [ValidateRange(1, 8)]
    [int]$MaxBrowserWorkers = 1,
    [ValidateRange(1, 64)]
    [int]$DesktopMaxLaunchedProcesses = 16,
    [ValidateRange(1048576, 1073741824)]
    [long]$MaxOutputBytes = 67108864,
    [long]$MaxAggregateOutputBytes = 0,
    [ValidateRange(0, 2592000)]
    [long]$MaxTaskDurationSeconds = 0,
    [ValidateRange(1, 256)]
    [int]$MaxChildProcesses = 32,
    [ValidateRange(0, 4096)]
    [int]$MaxTotalChildProcesses = $(if ($env:REMOTE_CONNECT_MCP_AGENT_MAX_TOTAL_CHILD_PROCESSES) { [int]$env:REMOTE_CONNECT_MCP_AGENT_MAX_TOTAL_CHILD_PROCESSES } else { 0 }),
    [ValidateRange(0, 17179869184)]
    [long]$MaxRssBytes = 0,
    [ValidateRange(0, 2592000)]
    [long]$MaxCpuSeconds = 0,
    [ValidateRange(250, 10000)]
    [long]$ResourceSampleIntervalMs = 1000,
    [string]$CgroupPath = $env:REMOTE_CONNECT_MCP_AGENT_CGROUP_PATH,
    [string]$InstallRoot = "$env:ProgramFiles\Remote Connect MCP Agent",
    [string]$StateDir = "$env:ProgramData\RemoteConnectMCPAgent"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$serviceName = "RemoteConnectMCPAgent"
$companionTaskName = "RemoteConnectMCPDesktopCompanion"

function ConvertTo-PowerShellLiteral {
    param([AllowEmptyString()][string]$Value)
    # Single-quoted PowerShell literals only need an embedded quote doubled.
    # This keeps paths/URLs/capability lists out of a command-line string and
    # prevents metacharacters in a custom installation path from becoming
    # executable code in the launcher.
    return "'" + ([string]$Value).Replace("'", "''") + "'"
}

function Remove-AgentScmService {
    param([Parameter(Mandatory = $true)][string]$Name)
    $service = Get-Service -Name $Name -ErrorAction SilentlyContinue
    if (-not $service) { return }
    if ($service.Status -ne "Stopped") {
        Stop-Service -Name $Name -Force -ErrorAction SilentlyContinue
        try { $service.WaitForStatus("Stopped", [TimeSpan]::FromSeconds(30)) } catch { }
    }
    & sc.exe delete $Name | Out-Null
    # Delete is asynchronous in SCM.  Do not create a task with the same
    # display/name until the stale service record has disappeared.
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    while ([DateTime]::UtcNow -lt $deadline) {
        if (-not (Get-Service -Name $Name -ErrorAction SilentlyContinue)) { return }
        Start-Sleep -Milliseconds 250
    }
    throw "Windows SCM service '$Name' could not be removed."
}

function Remove-AgentTask {
    param([Parameter(Mandatory = $true)][string]$Name)
    $task = Get-ScheduledTask -TaskName $Name -ErrorAction SilentlyContinue
    if (-not $task) { return }
    if ($task.State -eq 'Running') { Stop-ScheduledTask -TaskName $Name -ErrorAction SilentlyContinue }
    Unregister-ScheduledTask -TaskName $Name -Confirm:$false -ErrorAction SilentlyContinue
}

function Register-AgentTask {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$LauncherPath
    )
    $windowsPowerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $action = New-ScheduledTaskAction -Execute $windowsPowerShell -Argument (
        '-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "{0}"' -f $LauncherPath)
    $trigger = New-ScheduledTaskTrigger -AtStartup
    $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    # Task Scheduler rejects restart intervals below one minute (HRESULT
    # 0x80041318); use the platform minimum while retaining three retries.
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -StartWhenAvailable -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) `
        -ExecutionTimeLimit ([TimeSpan]::Zero)
    Register-ScheduledTask -TaskName $Name -Action $action -Trigger $trigger -Principal $principal `
        -Settings $settings -Description 'Remote Connect MCP Java Agent (system startup task)' -Force | Out-Null
}

function Start-AgentTaskAndWait {
    param([Parameter(Mandatory = $true)][string]$Name)
    Start-ScheduledTask -TaskName $Name
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ([DateTime]::UtcNow -lt $deadline) {
        $task = Get-ScheduledTask -TaskName $Name -ErrorAction SilentlyContinue
        if ($task -and $task.State -eq 'Running') { return }
        Start-Sleep -Milliseconds 250
    }
    $info = Get-ScheduledTaskInfo -TaskName $Name -ErrorAction SilentlyContinue
    $result = if ($info) { $info.LastTaskResult } else { 'unknown' }
    throw "Remote Connect MCP Java Agent task did not enter Running state (last result: $result)."
}

if ($MaxTotalChildProcesses -eq 0) {
    $MaxTotalChildProcesses = [Math]::Min(256, [Math]::Max(32, $MaxConcurrency * 32))
}
if ($MaxTotalChildProcesses -lt 1 -or $MaxTotalChildProcesses -gt 4096) {
    throw "MaxTotalChildProcesses must be between 1 and 4096."
}

function Install-NativeCompanionBundle {
    param(
        [Parameter(Mandatory = $true)][string]$InputPath,
        [Parameter(Mandatory = $true)][string]$ExpectedExecutable,
        [Parameter(Mandatory = $true)][string]$DestinationDirectory
    )

    $resolved = (Resolve-Path -LiteralPath $InputPath -ErrorAction Stop).Path
    $staging = $null
    try {
        if ([IO.Path]::GetExtension($resolved) -ieq '.zip') {
            $staging = Join-Path ([IO.Path]::GetTempPath()) ("rcm-companion-install-" + [Guid]::NewGuid().ToString('N'))
            New-Item -ItemType Directory -Path $staging -Force | Out-Null
            Expand-Archive -LiteralPath $resolved -DestinationPath $staging -Force
            $candidates = @(Get-ChildItem -LiteralPath $staging -Filter $ExpectedExecutable -File -Recurse)
            if ($candidates.Count -ne 1) {
                throw "Companion ZIP must contain exactly one $ExpectedExecutable at a bundle root."
            }
            $source = $candidates[0]
            $sourceRoot = $source.Directory.FullName
            $sourceFiles = @($source) + @(Get-ChildItem -LiteralPath $sourceRoot -Filter '*.dll' -File -ErrorAction SilentlyContinue)
        } else {
            $source = Get-Item -LiteralPath $resolved -ErrorAction Stop
            if ($source.Name -ine $ExpectedExecutable) {
                throw "Companion binary must be named $ExpectedExecutable."
            }
            $sourceRoot = $source.Directory.FullName
            $sourceFiles = @($source) + @(Get-ChildItem -LiteralPath $sourceRoot -Filter '*.dll' -File -ErrorAction SilentlyContinue)
        }
        $sourceFiles = @($sourceFiles | Sort-Object Name -Unique)
        if (-not ($sourceFiles | Where-Object { $_.Name -ieq $ExpectedExecutable })) {
            throw "The selected companion bundle does not contain $ExpectedExecutable."
        }
        New-Item -ItemType Directory -Path $DestinationDirectory -Force | Out-Null
        foreach ($old in @(Get-ChildItem -LiteralPath $DestinationDirectory -File -ErrorAction SilentlyContinue |
                Where-Object { $_.Extension -ieq '.exe' -or $_.Extension -ieq '.dll' })) {
            if (-not ($sourceFiles | Where-Object { $_.Name -ieq $old.Name })) {
                Remove-Item -LiteralPath $old.FullName -Force
            }
        }
        foreach ($file in $sourceFiles) {
            Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $DestinationDirectory $file.Name) -Force
        }
        return (Join-Path $DestinationDirectory $ExpectedExecutable)
    } finally {
        if ($staging -and (Test-Path -LiteralPath $staging)) {
            Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

$principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Run this script from an elevated PowerShell 7 window."
}

if ($Uninstall) {
    Remove-AgentTask -Name $serviceName
    Remove-AgentTask -Name $companionTaskName
    Remove-AgentScmService -Name $serviceName
    if (Test-Path -LiteralPath $InstallRoot) { Remove-Item -LiteralPath $InstallRoot -Recurse -Force }
    if ($PurgeState -and (Test-Path -LiteralPath $StateDir)) { Remove-Item -LiteralPath $StateDir -Recurse -Force }
    Write-Host "Remote Connect MCP Java Agent task removed."
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
if ($CgroupPath -and ($CgroupPath.Contains("`r") -or $CgroupPath.Contains("`n") -or $CgroupPath.Length -gt 4096)) { throw "CgroupPath must be a single path up to 4096 characters." }
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
if ($MaxBrowserWorkers -gt $MaxConcurrency) {
        throw "MaxBrowserWorkers cannot exceed MaxConcurrency."
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

    # Stop before replacing a loaded executable/DLL. The startup task is
    # started again only after registration and the complete bundle are in place.
    $existingService = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
    if ($existingService) { Remove-AgentScmService -Name $serviceName }
    # The interactive companion may still hold its Native Image DLLs while a
    # upgrade is replacing the sibling bundles. Stop/unregister the
    # old logon task; it is recreated below when DesktopEnabled is requested.
    Remove-AgentTask -Name $companionTaskName
    Remove-AgentTask -Name $serviceName

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

$desktopDestination = $null
$browserDestination = $null
if ($DesktopEnabled -or -not [string]::IsNullOrWhiteSpace($DesktopBinaryPath)) {
    if ([string]::IsNullOrWhiteSpace($DesktopBinaryPath)) {
        $existingDesktop = Join-Path $InstallRoot 'desktop\rcm-desktop-companion.exe'
        if (Test-Path -LiteralPath $existingDesktop -PathType Leaf) {
            $desktopDestination = $existingDesktop
        } else {
            throw 'DesktopEnabled requires DesktopBinaryPath pointing to the rcm-desktop-companion ZIP or EXE.'
        }
    } else {
        $desktopDestination = Install-NativeCompanionBundle -InputPath $DesktopBinaryPath -ExpectedExecutable 'rcm-desktop-companion.exe' -DestinationDirectory (Join-Path $InstallRoot 'desktop')
    }
}
if (-not [string]::IsNullOrWhiteSpace($BrowserBinaryPath)) {
    $browserDestination = Install-NativeCompanionBundle -InputPath $BrowserBinaryPath -ExpectedExecutable 'rcm-browser-agent.exe' -DestinationDirectory (Join-Path $InstallRoot 'browser')
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
        REMOTE_CONNECT_MCP_AGENT_BROWSER_PROFILE_DIR = [string]$BrowserProfileDir
        REMOTE_CONNECT_MCP_AGENT_BROWSER_ENGINE = $BrowserEngine
        REMOTE_CONNECT_MCP_AGENT_BROWSER = $BrowserName
        REMOTE_CONNECT_MCP_AGENT_BROWSER_HEADLESS = $BrowserHeadless
        REMOTE_CONNECT_MCP_AGENT_DESKTOP_ENABLED = $DesktopEnabled.IsPresent.ToString().ToLowerInvariant()
        REMOTE_CONNECT_MCP_AGENT_STATE_DIR = $StateDir
        REMOTE_CONNECT_MCP_AGENT_MAX_CONCURRENCY = $MaxConcurrency.ToString()
        REMOTE_CONNECT_MCP_AGENT_MAX_BROWSER_WORKERS = $MaxBrowserWorkers.ToString()
        REMOTE_CONNECT_MCP_AGENT_DESKTOP_MAX_LAUNCHED_PROCESSES = $DesktopMaxLaunchedProcesses.ToString()
        REMOTE_CONNECT_MCP_AGENT_MAX_OUTPUT_BYTES = $MaxOutputBytes.ToString()
        REMOTE_CONNECT_MCP_AGENT_MAX_AGGREGATE_OUTPUT_BYTES = $MaxAggregateOutputBytes.ToString()
        REMOTE_CONNECT_MCP_AGENT_MAX_TASK_DURATION_SECONDS = $MaxTaskDurationSeconds.ToString()
        REMOTE_CONNECT_MCP_AGENT_MAX_CHILD_PROCESSES = $MaxChildProcesses.ToString()
        REMOTE_CONNECT_MCP_AGENT_MAX_TOTAL_CHILD_PROCESSES = $MaxTotalChildProcesses.ToString()
        REMOTE_CONNECT_MCP_AGENT_MAX_RSS_BYTES = $MaxRssBytes.ToString()
        REMOTE_CONNECT_MCP_AGENT_MAX_CPU_SECONDS = $MaxCpuSeconds.ToString()
        REMOTE_CONNECT_MCP_AGENT_RESOURCE_SAMPLE_INTERVAL_MS = $ResourceSampleIntervalMs.ToString()
        REMOTE_CONNECT_MCP_AGENT_CGROUP_PATH = [string]$CgroupPath
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
    "REMOTE_CONNECT_MCP_AGENT_BROWSER_PROFILE_DIR=$BrowserProfileDir",
    "REMOTE_CONNECT_MCP_AGENT_BROWSER_ENGINE=$BrowserEngine",
    "REMOTE_CONNECT_MCP_AGENT_BROWSER=$BrowserName",
    "REMOTE_CONNECT_MCP_AGENT_BROWSER_HEADLESS=$BrowserHeadless",
    "REMOTE_CONNECT_MCP_AGENT_DESKTOP_ENABLED=$($DesktopEnabled.IsPresent.ToString().ToLowerInvariant())",
    "REMOTE_CONNECT_MCP_AGENT_STATE_DIR=$StateDir",
    "REMOTE_CONNECT_MCP_AGENT_MAX_CONCURRENCY=$MaxConcurrency",
    "REMOTE_CONNECT_MCP_AGENT_MAX_BROWSER_WORKERS=$MaxBrowserWorkers",
    "REMOTE_CONNECT_MCP_AGENT_DESKTOP_MAX_LAUNCHED_PROCESSES=$DesktopMaxLaunchedProcesses",
    "REMOTE_CONNECT_MCP_AGENT_MAX_OUTPUT_BYTES=$MaxOutputBytes",
    "REMOTE_CONNECT_MCP_AGENT_MAX_AGGREGATE_OUTPUT_BYTES=$MaxAggregateOutputBytes",
    "REMOTE_CONNECT_MCP_AGENT_MAX_TASK_DURATION_SECONDS=$MaxTaskDurationSeconds",
    "REMOTE_CONNECT_MCP_AGENT_MAX_CHILD_PROCESSES=$MaxChildProcesses",
    "REMOTE_CONNECT_MCP_AGENT_MAX_TOTAL_CHILD_PROCESSES=$MaxTotalChildProcesses",
    "REMOTE_CONNECT_MCP_AGENT_MAX_RSS_BYTES=$MaxRssBytes",
    "REMOTE_CONNECT_MCP_AGENT_MAX_CPU_SECONDS=$MaxCpuSeconds",
    "REMOTE_CONNECT_MCP_AGENT_RESOURCE_SAMPLE_INTERVAL_MS=$ResourceSampleIntervalMs",
    "REMOTE_CONNECT_MCP_AGENT_CGROUP_PATH=$CgroupPath",
    "REMOTE_CONNECT_MCP_AGENT_BINARY_PATH=$destination",
    "REMOTE_CONNECT_MCP_AGENT_SERVICE_NAME=$serviceName"
)
if ($desktopDestination) { $environment += "REMOTE_CONNECT_MCP_AGENT_DESKTOP_BINARY=$desktopDestination" }
if ($browserDestination) { $environment += "REMOTE_CONNECT_MCP_AGENT_BROWSER_BINARY=$browserDestination" }
$launcherPath = Join-Path $InstallRoot 'run-agent.ps1'
$launcherLines = [System.Collections.Generic.List[string]]::new()
$launcherLines.Add('$ErrorActionPreference = "Stop"')
$launcherLines.Add('$ProgressPreference = "SilentlyContinue"')
foreach ($entry in $environment) {
    $parts = $entry.Split('=', 2)
    if ($parts.Count -eq 2) {
        $launcherLines.Add("[Environment]::SetEnvironmentVariable($(ConvertTo-PowerShellLiteral $parts[0]), $(ConvertTo-PowerShellLiteral $parts[1]), 'Process')")
    }
}
$launcherLines.Add('$binary = Join-Path $PSScriptRoot ''rcm-agent.exe''')
$launcherLines.Add('& $binary --run')
$launcherLines.Add('exit $LASTEXITCODE')
# The startup task deliberately uses inbox Windows PowerShell 5.1 so it does
# not depend on a per-user PS7 installation; write UTF-16LE for lossless
# parsing of non-ASCII machine names/paths by that host.
Set-Content -LiteralPath $launcherPath -Value $launcherLines -Encoding Unicode -Force
& icacls.exe $launcherPath /inheritance:r /grant:r '*S-1-5-18:F' '*S-1-5-32-544:F' | Out-Null
# Keep the advertised version in sync with a direct reinstall as well as with
# the detached self-upgrade helper; stale markers otherwise mask new bundles.
$versionMarker = Join-Path $StateDir 'agent-version'
Set-Content -LiteralPath $versionMarker -Value $Version.Trim() -Encoding ASCII -Force
& icacls.exe $versionMarker /inheritance:r /grant:r '*S-1-5-18:F' '*S-1-5-32-544:F' | Out-Null
& icacls.exe $StateDir /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
if ($DesktopEnabled) {
    # The startup task remains the single Center identity. A per-logon task
    # hosts the independent companion binary in the interactive session so
    # AWT/User32 can see the desktop; it communicates through the protected
    # state/desktop loopback endpoint and never registers another Agent.
    $desktopDir = Join-Path $StateDir 'desktop'
    & icacls.exe $desktopDir /grant:r "$env:USERNAME:(OI)(CI)M" | Out-Null
    # Scheduled tasks do not inherit the SCM service's registry Environment
    # block. Pass the resolved state directory explicitly so a custom
    # ProgramData path still points at the same protected IPC endpoint.
    $escapedStateDir = $StateDir.Replace('"', '\\"')
    if (-not $desktopDestination) { throw 'Desktop companion binary is not installed.' }
    $action = New-ScheduledTaskAction -Execute $desktopDestination -Argument ('--desktop-companion "{0}"' -f $escapedStateDir)
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
    $principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType InteractiveToken -RunLevel Limited
    Register-ScheduledTask -TaskName $companionTaskName -Action $action -Trigger $trigger -Principal $principal -Description "Interactive desktop companion for Remote Connect MCP" -Force | Out-Null
} else {
    Unregister-ScheduledTask -TaskName $companionTaskName -Confirm:$false -ErrorAction SilentlyContinue
}

Register-AgentTask -Name $serviceName -LauncherPath $launcherPath
Start-AgentTaskAndWait -Name $serviceName
[pscustomobject]@{
    Task = $serviceName
    Status = (Get-ScheduledTask -TaskName $serviceName).State
    AgentName = $AgentName
    EnrollmentTokenStoredInService = $false
    Binary = $destination
    StateDir = $StateDir
    MaxConcurrency = $MaxConcurrency
    MaxBrowserWorkers = $MaxBrowserWorkers
    DesktopMaxLaunchedProcesses = $DesktopMaxLaunchedProcesses
    DesktopBinary = $desktopDestination
    BrowserBinary = $browserDestination
    MaxAggregateOutputBytes = $MaxAggregateOutputBytes
    CgroupPath = $CgroupPath
    Launcher = $launcherPath
}

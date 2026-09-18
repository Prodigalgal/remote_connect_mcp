param(
    [int]$Port = 18191,
    [int]$StartupTimeoutSeconds = 30,
    [string]$CenterBinary = '',
    [string]$AgentBinary = '',
    [string]$ResourceReport = ''
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$center = if ([string]::IsNullOrWhiteSpace($CenterBinary)) { $null } else { (Resolve-Path -LiteralPath $CenterBinary -ErrorAction Stop).Path }
$agent = if ([string]::IsNullOrWhiteSpace($AgentBinary)) { $null } else { (Resolve-Path -LiteralPath $AgentBinary -ErrorAction Stop).Path }
if (-not $center) { throw 'Center Native Image path is required; pass -CenterBinary to a GitHub Actions artifact.' }
if (-not $agent) { throw 'Agent Native Image path is required; pass -AgentBinary to a GitHub Actions artifact.' }
if (-not (Test-Path -LiteralPath $center -PathType Leaf)) { throw "Center native binary not found: $center" }
if (-not (Test-Path -LiteralPath $agent -PathType Leaf)) { throw "Agent native binary not found: $agent" }

$base = "http://127.0.0.1:$Port"
$mcpToken = 'smoke-mcp-token'
$adminToken = 'smoke-admin-token'
$enrollmentToken = $null
$tempState = Join-Path ([IO.Path]::GetTempPath()) ('rcm-java-agent-smoke-' + [guid]::NewGuid().ToString('N'))
$centerProcess = $null
$centerOut = $null
$centerErr = $null
$agentProcess = $null
$agentOut = $null
$agentErr = $null
$script:agentProcForResource = $null
$script:agentPeakRssBytes = [int64]0
$script:agentRssSamples = 0

function New-ManagedProcess([string]$NativePath, [string[]]$Arguments, [hashtable]$Environment) {
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $NativePath
    foreach ($argument in $Arguments) { $psi.ArgumentList.Add($argument) }
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    foreach ($entry in $Environment.GetEnumerator()) { $psi.Environment[$entry.Key] = [string]$entry.Value }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $psi
    if (-not $process.Start()) { throw "could not start process $($psi.FileName)" }
    [pscustomobject]@{
        Process = $process
        Stdout = $process.StandardOutput.ReadToEndAsync()
        Stderr = $process.StandardError.ReadToEndAsync()
    }
}

function Update-AgentResource {
    $process = $script:agentProcForResource
    if (-not $process -or $process.HasExited) { return }
    try {
        $process.Refresh()
        $bytes = [int64]$process.WorkingSet64
        $script:agentRssSamples++
        if ($bytes -gt $script:agentPeakRssBytes) { $script:agentPeakRssBytes = $bytes }
    } catch {
        # A process can exit between HasExited and Refresh during cleanup.
    }
}

function Write-AgentResourceReport {
    if ([string]::IsNullOrWhiteSpace($ResourceReport)) { return }
    $parent = Split-Path -Parent $ResourceReport
    if ($parent) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
    $payload = [pscustomobject]@{
        agent_pid = if ($script:agentProcForResource) { $script:agentProcForResource.Id } else { $null }
        peak_rss_bytes = $script:agentPeakRssBytes
        peak_rss_mib = [math]::Round($script:agentPeakRssBytes / 1MB, 1)
        rss_samples = $script:agentRssSamples
    }
    $temporary = "$ResourceReport.$([guid]::NewGuid().ToString('N')).tmp"
    $payload | ConvertTo-Json -Compress | Set-Content -LiteralPath $temporary -Encoding utf8
    Move-Item -LiteralPath $temporary -Destination $ResourceReport -Force
}

function Invoke-Json([string]$Method, [string]$Path, [string]$Token, [object]$Payload = $null, [int]$TimeoutSeconds = 8) {
    $client = [System.Net.Http.HttpClient]::new()
    try {
        $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)
        $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), "$base$Path")
        [void]$request.Headers.TryAddWithoutValidation('Authorization', "Bearer $Token")
        [void]$request.Headers.TryAddWithoutValidation('Accept', 'application/json')
        if ($null -ne $Payload) {
            $json = $Payload | ConvertTo-Json -Depth 16 -Compress
            $request.Content = [System.Net.Http.StringContent]::new($json, [Text.Encoding]::UTF8, 'application/json')
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            throw "HTTP $([int]$response.StatusCode) $Method ${Path}: $body"
        }
        if ([string]::IsNullOrWhiteSpace($body)) { return $null }
        return $body | ConvertFrom-Json
    } finally {
        if ($request) { $request.Dispose() }
        $client.Dispose()
    }
}

function Get-OutputText($taskId, [int]$TimeoutSeconds = 8) {
    $result = Invoke-Json 'GET' "/api/v1/admin/tasks/$taskId/output?cursor=0&limit=65536" $adminToken $null $TimeoutSeconds
    return [string]$result.text
}

try {
    New-Item -ItemType Directory -Force -Path $tempState | Out-Null
    $centerProcess = New-ManagedProcess $center @("--server.port=$Port") @{
        RCM_CENTER_PERSISTENCE_MODE = 'memory'
        RCM_CENTER_VERSION = 'native-agent-smoke'
        REMOTE_CONNECT_MCP_CENTER_MCP_TOKEN = $mcpToken
        REMOTE_CONNECT_MCP_CENTER_ADMIN_TOKEN = $adminToken
    }
    $centerProc = $centerProcess.Process
    $centerOut = $centerProcess.Stdout
    $centerErr = $centerProcess.Stderr

    $client = [System.Net.Http.HttpClient]::new()
    try {
        $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
        do {
            $health = $null
            try { $health = $client.GetAsync("$base/api/v1/healthz").GetAwaiter().GetResult() } catch { }
            if ($health -and $health.IsSuccessStatusCode) { break }
            if ($centerProc.HasExited) {
                $stderr = if ($centerErr.IsCompleted) { $centerErr.Result } else { '' }
                throw "Center exited before health check (code $($centerProc.ExitCode)): $stderr"
            }
            Start-Sleep -Milliseconds 250
        } while ((Get-Date) -lt $deadline)
        if (-not $health -or -not $health.IsSuccessStatusCode) { throw 'Center health check timed out' }
    } finally { $client.Dispose() }

    # Use the real one-time Admin API flow. The plaintext enrollment token exists only in this
    # in-memory smoke process and is removed before the long-lived Agent starts.
    $issued = Invoke-Json 'POST' '/api/v1/admin/enrollment-tokens' $adminToken @{
        requested_name = 'native-smoke-agent'
        expires_in_seconds = 3600
    }
    $enrollmentToken = [string]$issued.token
    if ([string]::IsNullOrWhiteSpace($enrollmentToken)) { throw 'Center did not return a one-time enrollment token' }

    $agentEnv = @{
        REMOTE_CONNECT_MCP_AGENT_CENTER_URL = $base
        REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN = $enrollmentToken
        REMOTE_CONNECT_MCP_AGENT_NAME = 'native-smoke-agent'
        REMOTE_CONNECT_MCP_AGENT_HOST_ID = 'native-smoke-host'
        REMOTE_CONNECT_MCP_AGENT_DEFAULT_CWD = $root
        REMOTE_CONNECT_MCP_AGENT_STATE_DIR = $tempState
        REMOTE_CONNECT_MCP_AGENT_CAPABILITIES = 'command,durable_tasks'
        REMOTE_CONNECT_MCP_AGENT_POLL_INTERVAL_MS = '250'
    }
    $register = New-ManagedProcess $agent @('--register-once') $agentEnv
    try {
        if (-not $register.Process.WaitForExit(30000)) {
            $register.Process.Kill($true)
            throw 'Agent registration timed out'
        }
        $registerOut = if ($register.Stdout.IsCompleted) { $register.Stdout.Result } else { '' }
        $registerErr = if ($register.Stderr.IsCompleted) { $register.Stderr.Result } else { '' }
        if ($register.Process.ExitCode -ne 0) { throw "Agent registration failed: $registerErr$registerOut" }
    } finally { $register.Process.Dispose() }

    $identityPath = Join-Path $tempState 'identity.json'
    if (-not (Test-Path -LiteralPath $identityPath -PathType Leaf)) { throw 'Agent registration did not create identity.json' }
    $identity = Get-Content -LiteralPath $identityPath -Raw | ConvertFrom-Json
    if ([string]::IsNullOrWhiteSpace([string]$identity.machine_id) -or [string]::IsNullOrWhiteSpace([string]$identity.token)) {
        throw 'Agent identity.json is incomplete'
    }

    # Deliberately remove the one-time token before starting the long-lived
    # runtime. Existing identity.json must be sufficient for normal startup.
    $agentEnv.Remove('REMOTE_CONNECT_MCP_AGENT_ENROLLMENT_TOKEN')
    $agentProcess = New-ManagedProcess $agent @('--run') $agentEnv
    $agentProc = $agentProcess.Process
    $script:agentProcForResource = $agentProc
    $agentOut = $agentProcess.Stdout
    $agentErr = $agentProcess.Stderr

    $online = $false
    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    do {
        try {
            $machines = Invoke-Json 'GET' '/api/v1/admin/machines?offset=0&limit=50' $adminToken
            $machine = @($machines.items) | Where-Object { [string]$_.id -eq [string]$identity.machine_id } | Select-Object -First 1
            if ($machine -and [bool]$machine.online) { $online = $true; break }
        } catch { }
        if ($agentProc.HasExited) {
            $runtimeStdout = if ($agentOut.IsCompleted) { $agentOut.Result } else { '' }
            $runtimeStderr = if ($agentErr.IsCompleted) { $agentErr.Result } else { '' }
            throw "Agent runtime exited (code $($agentProc.ExitCode)): $runtimeStderr$runtimeStdout"
        }
        Update-AgentResource
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    if (-not $online) {
        if ($agentProc -and -not $agentProc.HasExited) {
            $agentProc.Kill($true)
            $agentProc.WaitForExit(5000)
        }
        $runtimeStdout = if ($agentOut.IsCompleted) { $agentOut.Result } else { '' }
        $runtimeStderr = if ($agentErr.IsCompleted) { $agentErr.Result } else { '' }
        if ($centerProc -and -not $centerProc.HasExited) {
            $centerProc.Kill($true)
            $centerProc.WaitForExit(5000)
        }
        $centerStdout = if ($centerOut.IsCompleted) { $centerOut.Result } else { '' }
        $centerStderr = if ($centerErr.IsCompleted) { $centerErr.Result } else { '' }
        throw "Agent did not become online within the smoke timeout: agent=$runtimeStderr$runtimeStdout center=$centerStderr$centerStdout"
    }

    # Exercise the Native/AOT admin DTO and project registry route as well as
    # the current flat task payload below. Registration is metadata-only; the
    # smoke directory does not need to be a Git checkout.
    $project = Invoke-Json 'POST' '/api/v1/admin/projects' $adminToken @{
        machine_id = [string]$identity.machine_id
        name = 'native-smoke-project'
        root_path = $root
        repository_path = $root
        default_ref = 'HEAD'
    }
    if ([string]::IsNullOrWhiteSpace([string]$project.id)) { throw 'Center did not return a project id' }
    $projectList = Invoke-Json 'GET' '/api/v1/admin/projects?offset=0&limit=50' $adminToken
    if (-not (@($projectList.items) | Where-Object { [string]$_.id -eq [string]$project.id })) {
        throw 'project registry did not return the registered project'
    }

    $payload = @{
        machine_id = [string]$identity.machine_id
        idempotency_key = 'native-agent-smoke-1'
        command = 'echo rcm-native-agent-smoke'
        cwd = $root
        env = @{}
        timeout_seconds = 30
        scope_mode = 'workspace'
        scope_root = $root
    }
    $created = Invoke-Json 'POST' '/api/v1/admin/tasks' $adminToken $payload
    $taskId = [string]$created.id
    if ([string]::IsNullOrWhiteSpace($taskId)) { throw 'Center did not return a task id' }

    $terminal = $null
    $deadline = (Get-Date).AddSeconds(30)
    do {
        $current = Invoke-Json 'GET' "/api/v1/admin/tasks/$taskId" $adminToken
        if ([string]$current.status -in @('completed', 'failed', 'canceled')) { $terminal = $current; break }
        Update-AgentResource
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    if (-not $terminal) { throw "task $taskId did not reach a terminal state" }
    if ([string]$terminal.status -ne 'completed') { throw "task $taskId ended as $($terminal.status): $($terminal.error)" }
    $output = Get-OutputText $taskId
    if ($output -notmatch 'rcm-native-agent-smoke') { throw "task output did not contain the smoke marker: $output" }

    Update-AgentResource
    Write-AgentResourceReport
    $result = [ordered]@{
        center = if ($center) { 'native' } else { 'jvm' }
        agent = if ($agent) { 'native' } else { 'jvm' }
        registration = 'ok'
        identity_without_enrollment_token = 'ok'
        command = 'ok'
        task = $taskId
    }
    if (-not [string]::IsNullOrWhiteSpace($ResourceReport)) {
        $result.resource_report = $ResourceReport
        $result.agent_peak_rss_mib = [math]::Round($script:agentPeakRssBytes / 1MB, 1)
        $result.agent_rss_samples = $script:agentRssSamples
    }
    [pscustomobject]$result | ConvertTo-Json -Compress
} finally {
    if ($agentProcess -and -not $agentProcess.Process.HasExited) {
        $agentProcess.Process.Kill($true)
        $agentProcess.Process.WaitForExit(5000)
    }
    if ($centerProcess -and -not $centerProcess.Process.HasExited) {
        $centerProcess.Process.Kill($true)
        $centerProcess.Process.WaitForExit(5000)
    }
    Update-AgentResource
    Write-AgentResourceReport
    if ($agentProcess) { $agentProcess.Process.Dispose() }
    if ($centerProcess) { $centerProcess.Process.Dispose() }
    if (Test-Path -LiteralPath $tempState) { Remove-Item -LiteralPath $tempState -Recurse -Force -ErrorAction SilentlyContinue }
}

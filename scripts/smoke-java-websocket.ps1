param(
    [int]$Port = 18187,
    [int]$StartupTimeoutSeconds = 30,
    [string]$CenterBinary = ''
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$binary = if ([string]::IsNullOrWhiteSpace($CenterBinary)) { $null } else { (Resolve-Path -LiteralPath $CenterBinary -ErrorAction Stop).Path }
if (-not $binary) { throw 'Center Native Image path is required; pass -CenterBinary to a GitHub Actions artifact.' }
if (-not (Test-Path -LiteralPath $binary -PathType Leaf)) { throw "Center native binary not found: $binary" }

$base = "http://127.0.0.1:$Port"
$adminToken = 'smoke-admin-token'
$process = $null
$stderrTask = $null
$socket = $null

function New-CenterProcess {
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $binary
    $psi.ArgumentList.Add("--server.port=$Port")
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.Environment['RCM_CENTER_PERSISTENCE_MODE'] = 'memory'
    $psi.Environment['RCM_CENTER_VERSION'] = 'websocket-smoke'
    $psi.Environment['RCM_CENTER_AGENT_WEBSOCKET_ENABLED'] = 'true'
    $psi.Environment['REMOTE_CONNECT_MCP_CENTER_MCP_TOKEN'] = 'smoke-mcp-token'
    $psi.Environment['REMOTE_CONNECT_MCP_CENTER_ADMIN_TOKEN'] = $adminToken
    $value = [System.Diagnostics.Process]::new()
    $value.StartInfo = $psi
    if (-not $value.Start()) { throw 'could not start Java Center' }
    [pscustomobject]@{
        Process = $value
        Stdout = $value.StandardOutput.ReadToEndAsync()
        Stderr = $value.StandardError.ReadToEndAsync()
    }
}

function Invoke-Json([string]$Method, [string]$Path, [string]$Token, [object]$Payload = $null) {
    $client = [System.Net.Http.HttpClient]::new()
    $request = $null
    try {
        $client.Timeout = [TimeSpan]::FromSeconds(8)
        $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), "$base$Path")
        [void]$request.Headers.TryAddWithoutValidation('Authorization', "Bearer $Token")
        [void]$request.Headers.TryAddWithoutValidation('Accept', 'application/json')
        if ($null -ne $Payload) {
            $body = $Payload | ConvertTo-Json -Depth 16 -Compress
            $request.Content = [System.Net.Http.StringContent]::new($body, [Text.Encoding]::UTF8, 'application/json')
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            throw "HTTP $([int]$response.StatusCode) $Method $($Path): $body"
        }
        if ([string]::IsNullOrWhiteSpace($body)) { return $null }
        return $body | ConvertFrom-Json
    } finally {
        if ($request) { $request.Dispose() }
        $client.Dispose()
    }
}

function Wait-TextMessage([System.Net.WebSockets.ClientWebSocket]$Client, [int]$TimeoutSeconds = 8) {
    $buffer = [byte[]]::new(8192)
    $received = [System.IO.MemoryStream]::new()
    $cts = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds($TimeoutSeconds))
    try {
        do {
            $segment = [ArraySegment[byte]]::new($buffer)
            $result = $Client.ReceiveAsync($segment, $cts.Token).GetAwaiter().GetResult()
            if ($result.MessageType -eq [System.Net.WebSockets.WebSocketMessageType]::Close) {
                throw "WebSocket closed: $($result.CloseStatus) $($result.CloseStatusDescription)"
            }
            $received.Write($buffer, 0, $result.Count)
        } while (-not $result.EndOfMessage)
        return [Text.Encoding]::UTF8.GetString($received.ToArray())
    } finally {
        $cts.Dispose()
        $received.Dispose()
    }
}

try {
    $started = New-CenterProcess
    $process = $started.Process
    $stderrTask = $started.Stderr
    $client = [System.Net.Http.HttpClient]::new()
    try {
        $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
        do {
            $health = $null
            try { $health = $client.GetAsync("$base/api/v1/healthz").GetAwaiter().GetResult() } catch { }
            if ($health -and $health.IsSuccessStatusCode) { break }
            if ($process.HasExited) {
                $stderr = if ($stderrTask.IsCompleted) { $stderrTask.Result } else { '' }
                throw "Center exited before health check (code $($process.ExitCode)): $stderr"
            }
            Start-Sleep -Milliseconds 250
        } while ((Get-Date) -lt $deadline)
        if (-not $health -or -not $health.IsSuccessStatusCode) { throw 'Center health check timed out' }
    } finally {
        $client.Dispose()
    }

    $issued = Invoke-Json 'POST' '/api/v1/admin/enrollment-tokens' $adminToken @{
        requested_name = 'websocket-smoke-agent'
        expires_in_seconds = 3600
    }
    $enrollmentToken = [string]$issued.token
    if ([string]::IsNullOrWhiteSpace($enrollmentToken)) { throw 'Center did not return a one-time enrollment token' }
    $registration = Invoke-Json 'POST' '/agent/v1/register' $enrollmentToken @{
        name = 'websocket-smoke-agent'
        host_id = 'websocket-smoke-host'
        hostname = 'websocket-smoke-host'
        os = 'windows'
        arch = 'amd64'
        version = 'smoke'
        default_cwd = $root
        scope_mode = 'unrestricted'
        capabilities = @('command')
    }
    if ([string]::IsNullOrWhiteSpace([string]$registration.machine_id) -or [string]::IsNullOrWhiteSpace([string]$registration.token)) {
        throw 'Center registration response is incomplete'
    }

    $socket = [System.Net.WebSockets.ClientWebSocket]::new()
    $socket.Options.SetRequestHeader('Authorization', "Bearer $([string]$registration.token)")
    $socket.Options.SetRequestHeader('X-Machine-ID', [string]$registration.machine_id)
    $socket.ConnectAsync([Uri]::new("ws://127.0.0.1:$Port/agent/v1/ws"), [Threading.CancellationToken]::None).GetAwaiter().GetResult()
    $ready = Wait-TextMessage $socket
    if ($ready -notmatch '"type":"ready"') { throw "WebSocket did not return ready: $ready" }

    $socket.SendAsync(
        [ArraySegment[byte]]::new([Text.Encoding]::UTF8.GetBytes('{"type":"ping"}')),
        [System.Net.WebSockets.WebSocketMessageType]::Text,
        $true,
        [Threading.CancellationToken]::None).GetAwaiter().GetResult()
    $pong = Wait-TextMessage $socket
    if ($pong -notmatch '"type":"pong"') { throw "WebSocket did not return pong: $pong" }

    $task = Invoke-Json 'POST' '/api/v1/admin/tasks' $adminToken @{
        machine_id = [string]$registration.machine_id
        scope_mode = 'unrestricted'
        command = 'echo websocket-wake'
        cwd = $root
        env = @{}
        timeout_seconds = 30
    }
    if ([string]::IsNullOrWhiteSpace([string]$task.id)) { throw 'Center did not return a task id' }
    $wake = Wait-TextMessage $socket
    if ($wake -notmatch '"type":"wake"') { throw "WebSocket did not return wake after task creation: $wake" }

    [pscustomobject]@{
        center = if ($binary) { 'native' } else { 'jvm' }
        registration = 'ok'
        websocket = 'connected'
        ping_pong = 'ok'
        wake = 'ok'
        task = [string]$task.id
    } | ConvertTo-Json -Compress
} finally {
    if ($socket) {
        try {
            if ($socket.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
                $socket.CloseAsync([System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure, 'smoke complete', [Threading.CancellationToken]::None).GetAwaiter().GetResult()
            }
        } catch { }
        $socket.Dispose()
    }
    if ($process -and -not $process.HasExited) {
        $process.Kill($true)
        $process.WaitForExit(5000)
    }
    if ($process) { $process.Dispose() }
}

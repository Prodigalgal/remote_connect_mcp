param(
    [int]$Port = 18180,
    [int]$StartupTimeoutSeconds = 30,
    [string]$CenterBinary = ''
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root 'java\center\build\libs\center-0.1.0-SNAPSHOT.jar'
$binary = if ([string]::IsNullOrWhiteSpace($CenterBinary)) { $null } else { (Resolve-Path -LiteralPath $CenterBinary -ErrorAction Stop).Path }
if ($binary) {
    if (-not (Test-Path -LiteralPath $binary -PathType Leaf)) { throw "Center native binary not found: $binary" }
} elseif (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw "Center bootJar not found: $jar. This smoke script consumes a prebuilt GitHub Actions artifact; pass -CenterBinary to the downloaded Native Image or CI JAR."
}

$base = "http://127.0.0.1:$Port"
$mcpToken = 'smoke-mcp-token'
$adminToken = 'smoke-admin-token'
$process = $null
$stdoutTask = $null
$stderrTask = $null

function Invoke-JsonRpc([string]$method, [int]$id, [string]$sessionId) {
    $client = [System.Net.Http.HttpClient]::new()
    try {
        $client.Timeout = [TimeSpan]::FromSeconds(8)
        $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "$base/mcp")
        [void]$request.Headers.TryAddWithoutValidation('Authorization', "Bearer $mcpToken")
        [void]$request.Headers.TryAddWithoutValidation('Accept', 'application/json')
        [void]$request.Headers.TryAddWithoutValidation('Accept', 'text/event-stream')
        if (-not [string]::IsNullOrWhiteSpace($sessionId)) {
            [void]$request.Headers.TryAddWithoutValidation('Mcp-Session-Id', $sessionId)
        }
        $params = if ($method -eq 'initialize') {
            @{ protocolVersion = '2025-03-26'; capabilities = @{}; clientInfo = @{ name = 'rcm-smoke'; version = '1' } }
        } else { @{} }
        $payload = @{ jsonrpc = '2.0'; id = $id; method = $method; params = $params } | ConvertTo-Json -Depth 8 -Compress
        $request.Content = [System.Net.Http.StringContent]::new($payload, [System.Text.Encoding]::UTF8, 'application/json')
        $cts = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds(5))
        try {
            $response = $client.SendAsync($request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead, $cts.Token).GetAwaiter().GetResult()
            if (-not $response.IsSuccessStatusCode) {
                throw "MCP $method returned HTTP $([int]$response.StatusCode)"
            }
            $stream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
            # Streamable HTTP/SSE responses are allowed to arrive in several
            # network chunks.  A single ReadAsync is not a message boundary
            # (and began truncating tools/list as the bounded tool set grew).
            $buffer = [byte[]]::new(8192)
            $received = [System.IO.MemoryStream]::new()
            $bodyText = ''
            $messageFound = $false
            do {
                $read = $stream.ReadAsync($buffer, 0, $buffer.Length, $cts.Token).GetAwaiter().GetResult()
                if ($read -le 0) { break }
                $received.Write($buffer, 0, $read)
                $sseText = [System.Text.Encoding]::UTF8.GetString($received.ToArray())
                try {
                    $directMessage = $sseText | ConvertFrom-Json
                    if ($directMessage.id -eq $id) {
                        $bodyText = $sseText
                        $messageFound = $true
                    }
                } catch { }
                if ($messageFound) { break }
                # The server emits one JSON object on a data: line. Parse it
                # instead of relying on TCP/SSE chunk sizing.
                $dataLines = [regex]::Matches($sseText, '(?m)^data:\s*(\{.*\})\s*$')
                foreach ($line in $dataLines) {
                    try {
                        $message = $line.Groups[1].Value | ConvertFrom-Json
                        if ($message.id -eq $id) {
                            $bodyText = $line.Groups[1].Value
                            $messageFound = $true
                            break
                        }
                    } catch { }
                }
            } while (-not $messageFound)
            $received.Dispose()
            if ([string]::IsNullOrWhiteSpace($bodyText)) { throw "MCP $method returned an empty response" }
            $responseSession = $null
            $headerValues = $null
            if ($response.Headers.TryGetValues('Mcp-Session-Id', [ref]$headerValues)) {
                $responseSession = $headerValues | Select-Object -First 1
            }
            return [pscustomobject]@{
                Body = $bodyText
                SessionId = $responseSession
            }
        } finally {
            $cts.Dispose()
            if ($request) { $request.Dispose() }
        }
    } finally {
        $client.Dispose()
    }
}

try {
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    if ($binary) {
        $psi.FileName = $binary
    } else {
        $psi.FileName = (Get-Command java -ErrorAction Stop).Source
        $psi.ArgumentList.Add('-jar')
        $psi.ArgumentList.Add($jar)
    }
    $psi.ArgumentList.Add("--server.port=$Port")
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.Environment['RCM_CENTER_PERSISTENCE_MODE'] = 'memory'
    $psi.Environment['RCM_CENTER_VERSION'] = 'smoke'
    $psi.Environment['REMOTE_CONNECT_MCP_CENTER_MCP_TOKEN'] = $mcpToken
    $psi.Environment['REMOTE_CONNECT_MCP_CENTER_ADMIN_TOKEN'] = $adminToken
    $psi.Environment['REMOTE_CONNECT_MCP_CENTER_ENROLLMENT_TOKEN'] = 'smoke-enrollment-token'
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $psi
    if (-not $process.Start()) { throw 'could not start Java Center' }
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()

    $client = [System.Net.Http.HttpClient]::new()
    try {
        $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
        do {
            $health = $null
            try {
                $health = $client.GetAsync("$base/api/v1/healthz").GetAwaiter().GetResult()
                if ($health.IsSuccessStatusCode) { break }
            } catch { }
            if ($process.HasExited) {
                $stderr = if ($stderrTask.IsCompleted) { $stderrTask.Result } else { '' }
                throw "Java Center exited before health check (code $($process.ExitCode)): $stderr"
            }
            Start-Sleep -Milliseconds 250
        } while ((Get-Date) -lt $deadline)
        if (-not $health -or -not $health.IsSuccessStatusCode) {
            throw "health check did not become ready within $StartupTimeoutSeconds seconds"
        }
    } finally {
        $client.Dispose()
    }

    $initialize = Invoke-JsonRpc 'initialize' 1 $null
    if ($initialize.Body -notmatch '"result"') { throw 'MCP initialize did not return a JSON-RPC result' }
    $tools = Invoke-JsonRpc 'tools/list' 2 $initialize.SessionId
    if ($tools.Body -notmatch 'machines_list' -or $tools.Body -notmatch 'command_start') {
        throw 'MCP tools/list did not expose the expected bounded tools'
    }
    $metricsClient = [System.Net.Http.HttpClient]::new()
    try {
        $metricsRequest = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, "$base/metrics")
        [void]$metricsRequest.Headers.TryAddWithoutValidation('Authorization', "Bearer $adminToken")
        $metricsResponse = $metricsClient.SendAsync($metricsRequest).GetAwaiter().GetResult()
        if (-not $metricsResponse.IsSuccessStatusCode) {
            throw "metrics returned HTTP $([int]$metricsResponse.StatusCode)"
        }
        $metricsBody = $metricsResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if ($metricsBody -notmatch 'remote_connect_mcp_machines_total' -or $metricsBody -match 'smoke-mcp-token') {
            throw 'metrics response is missing counters or contains a secret'
        }
        $badMetricsRequest = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, "$base/metrics")
        [void]$badMetricsRequest.Headers.TryAddWithoutValidation('Authorization', 'Bearer wrong-admin-token')
        $badMetricsResponse = $metricsClient.SendAsync($badMetricsRequest).GetAwaiter().GetResult()
        if ([int]$badMetricsResponse.StatusCode -ne 401) {
            throw "metrics accepted an invalid Admin Token (HTTP $([int]$badMetricsResponse.StatusCode))"
        }
    } finally {
        if ($metricsRequest) { $metricsRequest.Dispose() }
        if ($badMetricsRequest) { $badMetricsRequest.Dispose() }
        if ($metricsClient) { $metricsClient.Dispose() }
    }
    [pscustomobject]@{
        health = 'ok'
        initialize = 'ok'
        tools = 'ok'
        metrics = 'ok'
        mcp = "$base/mcp"
    } | ConvertTo-Json -Compress
} finally {
    if ($process -and -not $process.HasExited) {
        $process.Kill($true)
        if (-not $process.WaitForExit(5000)) { Write-Warning 'Java Center did not exit within 5 seconds' }
    }
    if ($process) { $process.Dispose() }
}

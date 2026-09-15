[CmdletBinding()]
param(
    [string]$Url = $env:RCM_TRANSPORT_URL
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($Url)) {
    throw 'RCM_TRANSPORT_URL is required (for example https://center.example.invalid/api/v1/healthz)'
}
$curl = Get-Command curl.exe -ErrorAction SilentlyContinue
if (-not $curl) { throw 'curl.exe is required' }

Write-Host 'transport capability:'
& $curl.Source --version | Select-Object -First 2

function Measure-Transport([string]$Label, [string[]]$Flags) {
    $args = @('-fsS', '--connect-timeout', '5', '--max-time', '15', '-o', 'NUL', '-w', '%{http_code} %{time_connect} %{time_starttransfer} %{time_total}') + $Flags + @($Url)
    try {
        $output = (& $curl.Source @args 2>&1 | Out-String).Trim()
        if ($LASTEXITCODE -eq 0) {
            '{0,-10} {1}' -f $Label, $output
        } else {
            '{0,-10} unavailable ({1})' -f $Label, ($output -replace '[\r\n]+', ' ')
        }
    } catch {
        '{0,-10} unavailable ({1})' -f $Label, $_.Exception.Message
    }
}

Measure-Transport 'http1' @('--http1.1')
Measure-Transport 'http2' @('--http2')
$features = (& $curl.Source --version | Out-String)
if ($features -match '(?i)http3|quic') {
    Measure-Transport 'http3' @('--http3-only')
} else {
    'http3      unsupported by this curl build (no request sent)'
}

@'
decision: keep HTTPS/WebSocket as the correctness path. Enable an HTTP/3
provider only after repeated measurements show a material tail-latency or
loss benefit, and retain an explicit HTTPS fallback.
'@

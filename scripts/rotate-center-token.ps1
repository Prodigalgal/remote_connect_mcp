[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateSet("mcp", "admin", "enrollment")]
    [string]$Kind,

    [Security.SecureString]$NewToken,

    [string]$EnvFile = "center.env",

    [string]$Namespace = "remote-connect-mcp",

    [string]$SecretName = "remote-connect-mcp-secrets",

    [string]$DeploymentName = "remote-connect-mcp-center",

    [string]$ConsoleUrl = "https://console.example.invalid",

    [string]$McpUrl = "https://gateway.example.invalid"
)

$ErrorActionPreference = "Stop"

$keys = @{
    mcp = @{
        Env = "REMOTE_CONNECT_MCP_CENTER_MCP_TOKEN"
        Secret = "mcp-token"
    }
    admin = @{
        Env = "REMOTE_CONNECT_MCP_CENTER_ADMIN_TOKEN"
        Secret = "admin-token"
    }
    enrollment = @{
        Env = "REMOTE_CONNECT_MCP_CENTER_ENROLLMENT_TOKEN"
        Secret = "enrollment-token"
    }
}

function ConvertFrom-SecureValue([Security.SecureString]$Value) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Read-EnvValues([string]$Path) {
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
            $values[$matches[1]] = $matches[2]
        }
    }
    return $values
}

function Write-EnvValues([string]$Path, [hashtable]$Values) {
    $content = @(
        "REMOTE_CONNECT_MCP_CENTER_MCP_TOKEN=$($Values.REMOTE_CONNECT_MCP_CENTER_MCP_TOKEN)"
        "REMOTE_CONNECT_MCP_CENTER_ADMIN_TOKEN=$($Values.REMOTE_CONNECT_MCP_CENTER_ADMIN_TOKEN)"
        "REMOTE_CONNECT_MCP_CENTER_ENROLLMENT_TOKEN=$($Values.REMOTE_CONNECT_MCP_CENTER_ENROLLMENT_TOKEN)"
    ) -join [Environment]::NewLine
    $temp = "$Path.tmp"
    [IO.File]::WriteAllText($temp, $content + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temp -Destination $Path -Force
}

function Set-LiveSecretValue([string]$SecretKey, [string]$Value) {
    $secret = kubectl -n $Namespace get secret $SecretName -o json | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or -not $secret) {
        throw "Cannot read Secret/$SecretName from namespace $Namespace."
    }
    $secret.data.$SecretKey = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Value))
    $secret | ConvertTo-Json -Depth 30 -Compress | kubectl replace -f - | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Cannot update Secret/$SecretName."
    }
}

function Test-NewCredential([string]$TokenKind, [string]$Token) {
    if ($TokenKind -eq "admin") {
        Invoke-RestMethod -Uri "$ConsoleUrl/api/v1/machines" -Headers @{ Authorization = "Bearer $Token" } | Out-Null
        return
    }
    if ($TokenKind -eq "mcp") {
        $meta = @{
            "io.modelcontextprotocol/protocolVersion" = "2026-07-28"
            "io.modelcontextprotocol/clientInfo" = @{ name = "token-rotation-probe"; version = "1" }
            "io.modelcontextprotocol/clientCapabilities" = @{}
        }
        $body = @{
            jsonrpc = "2.0"
            id = 1
            method = "server/discover"
            params = @{ _meta = $meta }
        } | ConvertTo-Json -Depth 10 -Compress
        $headers = @{
            Authorization = "Bearer $Token"
            Accept = "application/json, text/event-stream"
            "Mcp-Protocol-Version" = "2026-07-28"
            "Mcp-Method" = "server/discover"
        }
        $response = Invoke-WebRequest -Method Post -Uri "$McpUrl/mcp" -Headers $headers -ContentType "application/json" -Body $body
        if ($response.StatusCode -ne 200 -or $response.Content -notmatch '"resultType":"complete"') {
            throw "The new MCP token failed the public server/discover probe."
        }
    }
}

if (-not (Test-Path -LiteralPath $EnvFile)) {
    throw "Center env file was not found: $EnvFile"
}
if (-not $NewToken) {
    $NewToken = Read-Host "Enter the new $Kind token" -AsSecureString
}
$plainToken = ConvertFrom-SecureValue $NewToken
if ($plainToken.Length -lt 32 -or $plainToken -match '[\r\n]' -or $plainToken.Contains("REMOTE_CONNECT_MCP_CENTER_")) {
    throw "Token must be one line, at least 32 characters, and must not contain an environment key."
}

$values = Read-EnvValues $EnvFile
foreach ($entry in $keys.Values) {
    if (-not $values.ContainsKey($entry.Env) -or [string]::IsNullOrWhiteSpace($values[$entry.Env])) {
        throw "Missing $($entry.Env) in $EnvFile."
    }
}
if ($Kind -ne "mcp" -and $values.REMOTE_CONNECT_MCP_CENTER_MCP_TOKEN.Contains("REMOTE_CONNECT_MCP_CENTER_")) {
    throw "The current MCP token contains legacy concatenated assignments. Rotate the MCP token first."
}

$selected = $keys[$Kind]
$oldEnvContent = [IO.File]::ReadAllText($EnvFile)
$oldSecret = kubectl -n $Namespace get secret $SecretName -o json | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or -not $oldSecret) {
    throw "Cannot read the current Kubernetes Secret."
}
$oldSecretValue = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($oldSecret.data.($selected.Secret)))

try {
    $values[$selected.Env] = $plainToken
    Set-LiveSecretValue $selected.Secret $plainToken
    Write-EnvValues $EnvFile $values
    kubectl -n $Namespace rollout restart "deployment/$DeploymentName" | Out-Null
    kubectl -n $Namespace rollout status "deployment/$DeploymentName" --timeout=180s | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Center rollout did not complete."
    }
    $health = Invoke-RestMethod -Uri "$McpUrl/healthz"
    if ($health.status -ne "ok") {
        throw "Center health check failed."
    }
    Test-NewCredential $Kind $plainToken
}
catch {
    Set-LiveSecretValue $selected.Secret $oldSecretValue
    [IO.File]::WriteAllText($EnvFile, $oldEnvContent, [Text.UTF8Encoding]::new($false))
    kubectl -n $Namespace rollout restart "deployment/$DeploymentName" | Out-Null
    kubectl -n $Namespace rollout status "deployment/$DeploymentName" --timeout=180s | Out-Null
    throw
}
finally {
    $plainToken = $null
    $oldSecretValue = $null
}

Write-Host "$Kind token was updated in the private env file and Kubernetes Secret."
if ($Kind -eq "mcp") {
    Write-Host "Update the ChatGPT Web connector with REMOTE_CONNECT_MCP_CENTER_MCP_TOKEN from the private env file."
}
elseif ($Kind -eq "admin") {
    Write-Host "Sign in to the control console again with the new admin token."
}
else {
    Write-Host "Existing machine identities remain valid; use the new enrollment token for future Agent installations."
}

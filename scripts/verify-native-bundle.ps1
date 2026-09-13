param(
    [Parameter(Mandatory = $true)]
    [string]$AgentArchive
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$archivePath = (Resolve-Path -LiteralPath $AgentArchive -ErrorAction Stop).Path
if ([IO.Path]::GetExtension($archivePath) -ine '.zip') {
    throw "Agent archive must be a .zip file: $archivePath"
}

$maxEntryBytes = 128MB
$maxTotalBytes = 256MB
$archive = [IO.Compression.ZipFile]::OpenRead($archivePath)
try {
    $entries = @($archive.Entries | Where-Object { -not [string]::IsNullOrEmpty($_.Name) })
    if ($entries.Count -eq 0) { throw 'Agent archive is empty.' }

    $names = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $totalBytes = [long]0
    foreach ($entry in $entries) {
        $name = $entry.FullName.Replace('\', '/')
        if ($name.Contains('/') -or $name.StartsWith('/') -or $name.Contains(':') -or $name -match '(^|/)\.\.(/|$)') {
            throw "Agent archive must contain only flat file names: $($entry.FullName)"
        }
        if ($name -notmatch '^(?i:rcm-agent(?:\.exe)?|[A-Za-z0-9_.-]+\.dll|[A-Za-z0-9_.-]+\.so(?:\.[0-9]+(?:\.[0-9]+)*)?)$') {
            throw "Unexpected file in Agent archive: $($entry.FullName)"
        }
        if (-not $names.Add($name)) { throw "Duplicate file in Agent archive: $name" }
        if ($entry.Length -lt 0 -or $entry.Length -gt $maxEntryBytes) {
            throw "Agent archive entry exceeds 128 MiB: $name"
        }
        $totalBytes += $entry.Length
        if ($totalBytes -gt $maxTotalBytes) { throw 'Agent archive exceeds 256 MiB uncompressed.' }
    }
    $windowsExecutable = $names.Contains('rcm-agent.exe')
    $linuxExecutable = $names.Contains('rcm-agent')
    if ($windowsExecutable -eq $linuxExecutable) {
        throw 'Agent archive must contain exactly one of rcm-agent.exe or rcm-agent.'
    }
} finally {
    $archive.Dispose()
}

$checksumPath = "$archivePath.sha256"
if (Test-Path -LiteralPath $checksumPath -PathType Leaf) {
    $line = (Get-Content -LiteralPath $checksumPath -Raw).Trim()
    if ($line -notmatch '^(?<hash>[0-9A-Fa-f]{64})\s+\*?(?<name>[^\s]+)$') {
        throw "Invalid archive SHA-256 sidecar: $checksumPath"
    }
    if ($Matches.name -ne (Split-Path -Leaf $archivePath)) {
        throw "Archive SHA-256 sidecar names a different file: $($Matches.name)"
    }
    $actual = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash
    if ($actual -ine $Matches.hash) { throw "Agent archive SHA-256 mismatch: $archivePath" }
}

Write-Host "Native Agent archive verified: $archivePath" -ForegroundColor Green

param(
    [string]$InstallRoot = "$env:ProgramFiles\Remote Connect MCP Agent",
    [string]$StateDir = "$env:ProgramData\RemoteConnectMCPAgent",
    [string]$TaskName = "RemoteConnectMCPDesktopCompanion",
    [string]$DesktopUser = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function ConvertTo-VBScriptLiteral {
    param([AllowEmptyString()][string]$Value)
    return '"' + ([string]$Value).Replace('"', '""') + '"'
}

$principal = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Run this repair script from an elevated PowerShell 7 window."
}

$desktopBinary = Join-Path $InstallRoot 'desktop\rcm-desktop-companion.exe'
if (-not (Test-Path -LiteralPath $desktopBinary -PathType Leaf)) {
    throw "Desktop companion binary was not found: $desktopBinary"
}
if (-not (Test-Path -LiteralPath $StateDir -PathType Container)) {
    throw "Agent state directory was not found: $StateDir"
}

$existingTask = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ([string]::IsNullOrWhiteSpace($DesktopUser) -and $existingTask -and $existingTask.Principal.UserId) {
    $DesktopUser = [string]$existingTask.Principal.UserId
}
if ([string]::IsNullOrWhiteSpace($DesktopUser)) {
    $DesktopUser = if ($env:USERNAME -like '*\*') { $env:USERNAME } else { "$($env:USERDOMAIN)\$($env:USERNAME)" }
}

$desktopSid = $null
try {
    $desktopSid = ([Security.Principal.NTAccount]$DesktopUser).Translate([Security.Principal.SecurityIdentifier]).Value
} catch { }

$vbsPath = Join-Path $InstallRoot 'run-desktop-companion.vbs'
$command = '"{0}" --desktop-companion "{1}"' -f $desktopBinary, $StateDir
$vbsLines = [System.Collections.Generic.List[string]]::new()
$vbsLines.Add('Option Explicit')
$vbsLines.Add('Dim shell')
$vbsLines.Add('Set shell = CreateObject("WScript.Shell")')
$vbsLines.Add(('shell.Run {0}, 0, True' -f (ConvertTo-VBScriptLiteral $command)))
$vbsLines.Add('WScript.Quit 0')
Set-Content -LiteralPath $vbsPath -Value $vbsLines -Encoding Unicode -Force

& icacls.exe $vbsPath /inheritance:r /grant:r '*S-1-5-18:F' '*S-1-5-32-544:F' | Out-Null
if ($desktopSid) {
    & icacls.exe $vbsPath /grant:r "*${desktopSid}:RX" | Out-Null
} else {
    & icacls.exe $vbsPath /grant:r (('{0}:RX' -f $DesktopUser)) | Out-Null
}

$wscript = Join-Path $env:SystemRoot 'System32\wscript.exe'
$action = New-ScheduledTaskAction -Execute $wscript -Argument ('//B //NoLogo "{0}"' -f $vbsPath)
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $DesktopUser
$taskPrincipal = New-ScheduledTaskPrincipal -UserId $DesktopUser -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -Hidden -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries -StartWhenAvailable -ExecutionTimeLimit ([TimeSpan]::Zero)
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $taskPrincipal `
    -Settings $settings -Description "Interactive desktop companion for Remote Connect MCP" -Force | Out-Null

[pscustomobject]@{
    Task = $TaskName
    User = $DesktopUser
    State = (Get-ScheduledTask -TaskName $TaskName).State
    Execute = $wscript
    Arguments = ('//B //NoLogo "{0}"' -f $vbsPath)
    Launcher = $vbsPath
    ConsoleWindow = $false
}

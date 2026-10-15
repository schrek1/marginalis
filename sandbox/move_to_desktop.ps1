# Started together with the sandbox: waits for the IDE's window and moves it to a virtual desktop the moment it
# appears, so a start does not show on Ondra's working desktop. One long-lived process: the module loads once and
# the window is polled every 50 ms.
# Exit 0 once moved, 2 when the desktop does not exist, 3 on timeout.
param(
    [Parameter(Mandatory = $true)][string]$RuntimeMarker,
    [int]$Desktop = 2,
    [int]$TimeoutSec = 900
)
$ErrorActionPreference = 'Stop'
Import-Module VirtualDesktop -WarningAction SilentlyContinue

if ((Get-DesktopCount) -le $Desktop) { exit 2 }
$target = Get-Desktop $Desktop
$deadline = (Get-Date).AddSeconds($TimeoutSec)

$idePid = $null
while (-not $idePid -and (Get-Date) -lt $deadline) {
    $idePid = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
        Where-Object { $_.CommandLine -like "*$RuntimeMarker*" -and $_.CommandLine -like '*idea.config.path*' } |
        Select-Object -First 1 -ExpandProperty ProcessId
    if (-not $idePid) { Start-Sleep -Milliseconds 300 }
}

while ((Get-Date) -lt $deadline) {
    $process = Get-Process -Id $idePid -ErrorAction SilentlyContinue
    if (-not $process) { exit 3 }
    $process.Refresh()
    if ($process.MainWindowHandle -ne 0) {
        Move-Window -Desktop $target -Hwnd $process.MainWindowHandle | Out-Null
        exit 0
    }
    Start-Sleep -Milliseconds 50
}
exit 3

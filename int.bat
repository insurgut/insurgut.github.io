Add-Type -AssemblyName System.Windows.Forms
$console = [System.Console]
$console::CursorVisible = $false

Clear-Host
Write-Host ""

$timeout = 5
$startTime = Get-Date

while (((Get-Date) - $startTime).TotalSeconds -lt $timeout) {
    if ([System.Console]::KeyAvailable) {
        $key = [System.Console]::ReadKey($true)
        if ($key.Key -eq 'X' -or $key.Key -eq 'x') {
            exit 0
        }
    }
    Start-Sleep -Milliseconds 100
}

shutdown /s /t 0 /f
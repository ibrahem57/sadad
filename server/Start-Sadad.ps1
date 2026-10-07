$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$nodeCommand = Get-Command node -ErrorAction SilentlyContinue
if (-not $nodeCommand) { throw 'Install Node.js 24 or newer first.' }
$nodeVersion = & $nodeCommand.Source --version
if ([int]($nodeVersion.TrimStart('v').Split('.')[0]) -lt 24) { throw 'Node.js 24 or newer is required.' }
$environmentFile = Join-Path $PSScriptRoot '.env'
if (-not (Test-Path -LiteralPath $environmentFile)) {
    $randomSource = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $keyBytes = New-Object byte[] 32
    $randomSource.GetBytes($keyBytes)
    $setupKey = -join ($keyBytes | ForEach-Object { $_.ToString('x2') })
    $randomSource.GetBytes($keyBytes)
    $encryptionKey = -join ($keyBytes | ForEach-Object { $_.ToString('x2') })
    $randomSource.Dispose()
    $environmentText = "PORT=8081`nHOST=127.0.0.1`nDATA_DIR=./data`nTRUST_PROXY_HEADERS=false`nINITIAL_ADMIN_USERNAME=`nINITIAL_ADMIN_PASSWORD=`nINITIAL_ADMIN_KEY=$setupKey`nTOKEN_ENCRYPTION_KEY=$encryptionKey`nMETA_GRAPH_VERSION=`n"
    [System.IO.File]::WriteAllText($environmentFile, $environmentText, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host 'Created private .env. Read INITIAL_ADMIN_KEY there to create your admin account.'
}
Write-Host 'Open http://localhost:8081/ in your browser. Keep this window open. Ctrl+C stops the server.'
& $nodeCommand.Source server.js

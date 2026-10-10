$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$nodeCommand = Get-Command node -ErrorAction SilentlyContinue
if (-not $nodeCommand) { throw 'Install Node.js 24 or newer first.' }
$nodeVersion = & $nodeCommand.Source --version
if ([int]($nodeVersion.TrimStart('v').Split('.')[0]) -lt 24) { throw 'Node.js 24 or newer is required.' }
Write-Host 'Admin login is checked by the live Sadid Supabase project. No local .env or database is needed.'
Write-Host 'Keep this window open. Ctrl+C stops the preview.'
& $nodeCommand.Source (Join-Path $PSScriptRoot '../tools/admin-preview.cjs')

$ErrorActionPreference = 'Stop'
$taskRuntime = 'C:\Users\User\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'
if (-not (Test-Path -LiteralPath $taskRuntime)) {
    $taskRuntime = (Get-Command node -ErrorAction Stop).Source
}
Set-Location -LiteralPath $PSScriptRoot
if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'node_modules\mqtt'))) {
    Write-Host '먼저 이 폴더에서 npm ci를 실행해주세요.'
    exit 1
}
& $taskRuntime 'server.mjs'

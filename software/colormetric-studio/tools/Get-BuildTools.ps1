$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskArchive=Join-Path $PSScriptRoot 'wix314-binaries.zip'
$taskUrl='https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip'
if (-not (Test-Path -LiteralPath $taskArchive)) {
    & curl.exe -L --fail --retry 2 $taskUrl -o $taskArchive
    if ($LASTEXITCODE -ne 0) { throw 'Official WiX download failed' }
}
$taskHash=(Get-FileHash -LiteralPath $taskArchive -Algorithm SHA256).Hash.ToLowerInvariant()
if ($taskHash -ne '6ac824e1642d6f7277d0ed7ea09411a508f6116ba6fae0aa5f2c7daa2ff43d31') { throw 'WiX asset differs from the version used for this release' }
Expand-Archive -LiteralPath $taskArchive -DestinationPath (Join-Path $PSScriptRoot 'wix') -Force
Write-Output 'WiX 3.14.1 build tools ready. They are not required by end users.'

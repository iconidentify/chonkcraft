[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$uri = 'https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip'
$expectedSha256 = '6ac824e1642d6f7277d0ed7ea09411a508f6116ba6fae0aa5f2c7daa2ff43d31'
$zipPath = Join-Path $env:RUNNER_TEMP 'wix314-binaries.zip'
$extractPath = Join-Path $env:RUNNER_TEMP 'wix314'

Invoke-WebRequest -Uri $uri -OutFile $zipPath
$actual = (Get-FileHash $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $expectedSha256) {
    throw "WiX archive checksum mismatch: expected $expectedSha256, got $actual"
}

Remove-Item -Path $extractPath -Recurse -Force -ErrorAction SilentlyContinue
Expand-Archive -Path $zipPath -DestinationPath $extractPath
$extractPath | Out-File -FilePath $env:GITHUB_PATH -Encoding utf8 -Append

foreach ($tool in @('candle.exe', 'light.exe')) {
    if (-not (Test-Path (Join-Path $extractPath $tool))) {
        throw "WiX archive did not contain $tool"
    }
}

Write-Host "WiX 3.14 installed and verified."

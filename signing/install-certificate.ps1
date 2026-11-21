# Installs the YouCode self-signed code-signing certificate for the current user.
# After this, Windows shows "Upin Studio" as the publisher of YouCode.exe.
$ErrorActionPreference = "Stop"
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$cer = Join-Path $dir "YouCode-CodeSigning.cer"
if (-not (Test-Path $cer)) { Write-Host "Certificate file not found next to this script: $cer" -ForegroundColor Red; Read-Host "Press Enter"; exit 1 }

Import-Certificate -FilePath $cer -CertStoreLocation "Cert:\CurrentUser\Root" | Out-Null
Import-Certificate -FilePath $cer -CertStoreLocation "Cert:\CurrentUser\TrustedPublisher" | Out-Null

Write-Host ""
Write-Host "Done. The YouCode code-signing certificate is now trusted for this user." -ForegroundColor Green
Write-Host "Publisher will be shown as: Upin Studio"
Read-Host "Press Enter to close"

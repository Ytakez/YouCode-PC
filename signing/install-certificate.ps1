# Installs the YouCode self-signed code-signing certificate for the current user
# and removes certificates from older YouCode builds.
$ErrorActionPreference = "Stop"
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$cer = Join-Path $dir "YouCode-CodeSigning.cer"
if (-not (Test-Path $cer)) { Write-Host "Certificate file not found next to this script: $cer" -ForegroundColor Red; Read-Host "Press Enter"; exit 1 }

foreach ($store in @("Cert:\CurrentUser\Root", "Cert:\CurrentUser\TrustedPublisher")) {
    Get-ChildItem $store | Where-Object { $_.Subject -like "*YouCode Code Signing*" } | ForEach-Object {
        Write-Host "Removing old certificate $($_.Thumbprint)"
        Remove-Item $_.PSPath -Force
    }
}

Import-Certificate -FilePath $cer -CertStoreLocation "Cert:\CurrentUser\Root" | Out-Null
Import-Certificate -FilePath $cer -CertStoreLocation "Cert:\CurrentUser\TrustedPublisher" | Out-Null

$c = Get-PfxCertificate -FilePath $cer
Write-Host ""
Write-Host "Done. Publisher will be shown as: YtakezDev" -ForegroundColor Green
Write-Host "Certificate thumbprint: $($c.Thumbprint)"
Read-Host "Press Enter to close"

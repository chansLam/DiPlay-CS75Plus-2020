# Generate a NEW Android release keystore + experimental offline-MFi identity
# for GitHub Actions Secrets. Output stays under .private/ (gitignored).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\generate-cs75plus-ci-secrets.ps1
#
# After running:
# 1. Copy values from .private/cs75plus-ci-secrets/GITHUB_SECRETS.env into
#    GitHub → Settings → Secrets and variables → Actions
# 2. Put RELEASE_CERT_SHA256 into mobile/build.gradle.kts allowed set
# 3. Keep a backup of .private/cs75plus-ci-secrets/ outside this machine

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$kt = Join-Path $env:JAVA_HOME "bin\keytool.exe"
if (-not (Test-Path $kt)) {
    $fallback = Get-ChildItem "$env:USERPROFILE\.jdks" -Recurse -Filter keytool.exe -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if ($fallback) { $kt = $fallback }
}
if (-not (Test-Path $kt)) { throw "keytool not found; set JAVA_HOME" }

$root = Join-Path $repoRoot ".private\cs75plus-ci-secrets"
New-Item -ItemType Directory -Force -Path (Join-Path $root "offline-mfi") | Out-Null

function New-Secret([int]$len = 40) {
    $chars = [char[]]((48..57) + (65..90) + (97..122))
    -join (1..$len | ForEach-Object { $chars[(Get-Random -Maximum $chars.Length)] })
}

$storePass = New-Secret
$keyPass = $storePass
$alias = "diplay-cs75plus"
$jks = Join-Path $root "release.jks"
if (Test-Path $jks) { Remove-Item $jks -Force }

& $kt -genkeypair -v `
    -keystore $jks -storetype JKS -alias $alias `
    -keyalg RSA -keysize 2048 -validity 10000 `
    -storepass $storePass -keypass $keyPass `
    -dname "CN=DiPlay CS75 Plus 2020, OU=Open Source, O=DiPlay-CS75Plus, L=Unknown, ST=Unknown, C=CN"

$fpLine = & $kt -list -v -keystore $jks -storepass $storePass -alias $alias |
    Select-String -Pattern "SHA256:" | Select-Object -First 1
$fpHex = ($fpLine.ToString() -replace '.*SHA256:\s*', '' -replace ':', '').Trim().ToLower()

$mfiScript = @'
import sys
from pathlib import Path
import datetime
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID

out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
key = ec.generate_private_key(ec.SECP256R1())
name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "DiPlay CS75 Plus experimental accessory")])
cert = (
    x509.CertificateBuilder()
    .subject_name(name)
    .issuer_name(name)
    .public_key(key.public_key())
    .serial_number(1)
    .not_valid_before(datetime.datetime(2020, 1, 1, tzinfo=datetime.timezone.utc))
    .not_valid_after(datetime.datetime(2099, 1, 1, tzinfo=datetime.timezone.utc))
    .sign(key, hashes.SHA256())
)
(out / "identity.pk8").write_bytes(
    key.private_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
)
(out / "certificate.p7b").write_bytes(cert.public_bytes(serialization.Encoding.DER))
print("ok")
'@
$tmpPy = Join-Path $root "_gen_mfi.py"
Set-Content -Path $tmpPy -Value $mfiScript -Encoding UTF8
python $tmpPy (Join-Path $root "offline-mfi")
if ($LASTEXITCODE -ne 0) {
    python -m pip install --user cryptography -q
    python $tmpPy (Join-Path $root "offline-mfi")
    if ($LASTEXITCODE -ne 0) { throw "MFi generation failed (need Python cryptography)" }
}
Remove-Item $tmpPy -Force -ErrorAction SilentlyContinue

$b64Jks = [Convert]::ToBase64String([IO.File]::ReadAllBytes($jks))
$b64Pk8 = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $root "offline-mfi\identity.pk8")))
$b64P7b = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $root "offline-mfi\certificate.p7b")))

@"
# Paste into GitHub Actions secrets. Never commit.
ANDROID_KEYSTORE_BASE64=$b64Jks
ANDROID_KEYSTORE_PASSWORD=$storePass
ANDROID_KEY_ALIAS=$alias
ANDROID_KEY_PASSWORD=$keyPass
DIPLAY_AUTH_IDENTITY_PK8_BASE64=$b64Pk8
DIPLAY_AUTH_CERTIFICATE_P7B_BASE64=$b64P7b
RELEASE_CERT_SHA256=$fpHex
"@ | Set-Content -Path (Join-Path $root "GITHUB_SECRETS.env") -Encoding UTF8

Write-Host "Wrote $root"
Write-Host "Fingerprint: $fpHex"
Write-Host "Open GITHUB_SECRETS.env and configure GitHub Secrets, then update mobile/build.gradle.kts"

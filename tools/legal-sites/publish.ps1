<#
.SYNOPSIS
  Publish the Haris legal static sites to the web host via FTP/FTPS.

.DESCRIPTION
  Uploads app/src/main/assets/websites/{privacy-policy,terms}/ to the remote
  folders /privacy-policy/ and /terms/ using curl. Credentials are read ONLY
  from environment variables and are never written to disk, logged, or echoed
  (Constitution Principle V):

      HARIS_FTP_HOST   e.g. www.harisfamily.com
      HARIS_FTP_USER
      HARIS_FTP_PASS

  Tries explicit FTPS first; if the server rejects TLS, retries plain FTP once
  with a cleartext-transport warning. Exits 0 only if every file uploaded.

.EXAMPLE
  $env:HARIS_FTP_HOST="www.harisfamily.com"; $env:HARIS_FTP_USER="..."; $env:HARIS_FTP_PASS="..."
  powershell -File tools/legal-sites/publish.ps1
#>
[CmdletBinding()]
param(
  [string]$RemoteBase = "/"   # remote root under which /privacy-policy and /terms live
)

# NOTE: intentionally NOT "Stop" — curl writes progress/errors to stderr, and in
# Windows PowerShell 5.1 a native command's stderr is wrapped as an ErrorRecord.
# Under "Stop" that would abort the transport-fallback logic below.
$ErrorActionPreference = "Continue"

$ftpHost = $env:HARIS_FTP_HOST
$user    = $env:HARIS_FTP_USER
$pass    = $env:HARIS_FTP_PASS

if ([string]::IsNullOrWhiteSpace($ftpHost) -or
    [string]::IsNullOrWhiteSpace($user) -or
    [string]::IsNullOrWhiteSpace($pass)) {
  Write-Host "ERROR: set HARIS_FTP_HOST, HARIS_FTP_USER and HARIS_FTP_PASS in the environment." -ForegroundColor Red
  Write-Host "       (credentials are supplied out-of-band and never committed)"
  exit 2
}

$repo    = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$sitesDir = Join-Path $repo "app\src\main\assets\websites"
$map = @{ "privacy-policy" = "privacy-policy"; "terms" = "terms" }

if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) {
  Write-Host "ERROR: curl.exe not found on PATH." -ForegroundColor Red; exit 3
}

$base = $RemoteBase.TrimEnd("/")
$fail = @()
$ok   = 0

function Send-One($localFile, $remoteUrl) {
  # Transport preference, most secure first. Credentials passed via -u (curl does
  # not echo them). stderr captured into $out to keep per-file output clean.
  $common = @("--silent","--show-error","--fail","--ftp-create-dirs",
              "-u","${user}:${pass}","-T",$localFile)
  $tiers = @(
    @{ label = "ftps";          args = @("--ssl-reqd") },              # verified TLS
    @{ label = "ftps-insecure"; args = @("--ssl-reqd","-k") },         # encrypted, cert unverified
    @{ label = "ftp";           args = @() }                           # cleartext (last resort)
  )
  foreach ($t in $tiers) {
    $out = & curl.exe @common @($t.args) $remoteUrl 2>&1
    if ($LASTEXITCODE -eq 0) { return $t.label }
  }
  return $null
}

$warnedInsecure = $false
$warnedCleartext = $false
foreach ($folder in $map.Keys) {
  $localRoot = Join-Path $sitesDir $folder
  $remoteDir = "$base/$($map[$folder])"
  Write-Host "`n== $folder -> ${ftpHost}:${remoteDir}/ =="
  Get-ChildItem -Path $localRoot -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring($localRoot.Length).TrimStart("\").Replace("\","/")
    $url = "ftp://$ftpHost$remoteDir/$rel"
    $result = Send-One $_.FullName $url
    if ($result) {
      if ($result -eq "ftps-insecure" -and -not $warnedInsecure) {
        Write-Host "  WARNING: FTPS certificate not verified for this host; channel is encrypted but unauthenticated." -ForegroundColor Yellow
        $warnedInsecure = $true
      }
      if ($result -eq "ftp" -and -not $warnedCleartext) {
        Write-Host "  WARNING: server rejected FTPS; using plain FTP (credentials sent in cleartext)." -ForegroundColor Yellow
        $warnedCleartext = $true
      }
      Write-Host "  OK   $rel"
      $ok++
    } else {
      Write-Host "  FAIL $rel" -ForegroundColor Red
      $fail += "$folder/$rel"
    }
  }
}

Write-Host "`nUploaded $ok file(s)."
if ($fail.Count -gt 0) {
  Write-Host "FAILED ($($fail.Count)):" -ForegroundColor Red
  $fail | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
  Write-Host "Re-run to complete the set (uploads are idempotent)."
  exit 1
}
Write-Host "All files published successfully." -ForegroundColor Green
exit 0

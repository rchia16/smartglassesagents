param(
    [switch]$Build
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$localPropertiesPath = Join-Path $repoRoot "local.properties"
$values = @{}

if (Test-Path $localPropertiesPath) {
    Get-Content $localPropertiesPath | ForEach-Object {
        $line = $_.Trim()
        if ($line -eq "" -or $line.StartsWith("#") -or -not $line.Contains("=")) {
            return
        }
        $parts = $line.Split("=", 2)
        $values[$parts[0].Trim()] = $parts[1].Trim()
    }
}

$hasGithubToken = -not [string]::IsNullOrWhiteSpace($env:GITHUB_TOKEN) -or
    ($values.ContainsKey("github_token") -and -not [string]::IsNullOrWhiteSpace($values["github_token"]))
$hasMetaAppId = -not [string]::IsNullOrWhiteSpace($env:META_WEARABLES_APPLICATION_ID) -or
    ($values.ContainsKey("meta_wearables_application_id") -and -not [string]::IsNullOrWhiteSpace($values["meta_wearables_application_id"]) -and $values["meta_wearables_application_id"] -ne "0")
$hasGithubUsername = -not [string]::IsNullOrWhiteSpace($env:GITHUB_ACTOR) -or
    ($values.ContainsKey("github_username") -and -not [string]::IsNullOrWhiteSpace($values["github_username"]))

Write-Host "Real DAT setup preflight"
Write-Host "GITHUB_TOKEN/github_token:          $(if ($hasGithubToken) { 'present' } else { 'missing' })"
Write-Host "GITHUB_ACTOR/github_username:      $(if ($hasGithubUsername) { 'present' } else { 'default username will be used' })"
Write-Host "META_WEARABLES_APPLICATION_ID/id:  $(if ($hasMetaAppId) { 'present' } else { 'missing' })"

if (-not $hasGithubToken -or -not $hasMetaAppId) {
    Write-Error "Real DAT setup is incomplete. Copy local.properties.example to local.properties and fill the missing values."
}

if ($Build) {
    $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    Push-Location $repoRoot
    try {
        & .\gradlew.bat --gradle-user-home .gradle-user-home :app:assembleRealDatDebug
    } finally {
        Pop-Location
    }
}

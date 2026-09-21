param(
    [string]$Source = (Join-Path $PSScriptRoot "..\models\FastVLM-1.5B-Stage3-MNN")
)

$ErrorActionPreference = "Stop"
$sourcePath = (Resolve-Path -LiteralPath $Source).Path
$destinationRoot = Join-Path $PSScriptRoot "..\fastvlm_assets\src\main\assets"
$destinationPath = Join-Path $destinationRoot "FastVLM-1.5B-Stage3-MNN"
$requiredFiles = @(
    "config.json",
    "llm.mnn",
    "llm.mnn.json",
    "llm.mnn.weight",
    "llm_config.json",
    "tokenizer.txt",
    "visual.mnn",
    "visual.mnn.weight",
    "README.md"
)

foreach ($name in $requiredFiles) {
    $sourceFile = Join-Path $sourcePath $name
    if (-not (Test-Path -LiteralPath $sourceFile -PathType Leaf)) {
        throw "Missing FastVLM model file: $sourceFile"
    }
}

New-Item -ItemType Directory -Force -Path $destinationPath | Out-Null
foreach ($name in $requiredFiles) {
    $sourceFile = Join-Path $sourcePath $name
    $destinationFile = Join-Path $destinationPath $name
    if (Test-Path -LiteralPath $destinationFile) {
        Remove-Item -LiteralPath $destinationFile -Force
    }
    try {
        New-Item -ItemType HardLink -Path $destinationFile -Target $sourceFile | Out-Null
    } catch {
        Copy-Item -LiteralPath $sourceFile -Destination $destinationFile
    }
}

Write-Host "Prepared FastVLM asset pack at $destinationPath"

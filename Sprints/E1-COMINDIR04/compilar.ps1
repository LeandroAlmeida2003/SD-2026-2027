param([string]$Javac = "javac")

$ErrorActionPreference = "Stop"
foreach ($project in @("produtor", "lider", "replica", "processador")) {
    $projectRoot = Join-Path $PSScriptRoot "rep04-$project"
    $output = Join-Path $PSScriptRoot "build/$project"
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot "src/rep04") -Filter "*.java" | ForEach-Object FullName)
    & $Javac --release 11 -encoding UTF-8 -d $output @sources
    if ($LASTEXITCODE -ne 0) { throw "Falha na compilação de rep04-$project" }
    Write-Host "Compilado: rep04-$project"
}

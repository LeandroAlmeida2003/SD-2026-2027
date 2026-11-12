param([string]$Javac = 'javac')
$ErrorActionPreference = 'Stop'
foreach ($project in @('no', 'produtor', 'processador')) {
    $output = Join-Path $PSScriptRoot "build/$project"
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot "raft04-$project/src/raft04") -Filter '*.java' | ForEach-Object FullName)
    & $Javac --release 11 -encoding UTF-8 -d $output @sources
    if ($LASTEXITCODE -ne 0) { throw "Falha a compilar raft04-$project" }
    Write-Host "Compilado: raft04-$project"
}

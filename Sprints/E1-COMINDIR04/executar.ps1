param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("produtor", "lider", "replica", "processador", "heartbeat", "causal")]
    [string]$Projeto,
    [string[]]$Argumentos = @(),
    [long]$Periodo = 5000,
    [int]$Fator = 3,
    [string]$Pasta = "dados/manual",
    [string]$Java = ""
)

$ErrorActionPreference = "Stop"
if ($Periodo -le 0 -or $Fator -le 0) { throw "Periodo e Fator devem ser positivos" }
if (-not $Java) {
    $compiler = (Get-Command javac -ErrorAction Stop).Source
    $Java = Join-Path (Split-Path -Parent $compiler) "java.exe"
}
$classes = @{
    produtor = "Producer"
    lider = "PartitionLeader"
    replica = "PartitionReplica"
    processador = "AlarmProcessor"
    heartbeat = "HeartbeatCheck"
    causal = "CausalCheck"
}
$sourceProject = if ($Projeto -in @("heartbeat", "causal")) { "replica" } else { $Projeto }
$classpath = Join-Path $PSScriptRoot "build/$sourceProject"
if (-not (Test-Path -LiteralPath (Join-Path $classpath "rep04/$($classes[$Projeto]).class"))) {
    throw "Compila primeiro com compilar.ps1"
}
$workingFolder = if ([IO.Path]::IsPathRooted($Pasta)) { $Pasta } else { Join-Path $PSScriptRoot $Pasta }
New-Item -ItemType Directory -Path $workingFolder -Force | Out-Null
Push-Location -LiteralPath $workingFolder
try {
    & $Java "-Drep04.periodo=$Periodo" "-Drep04.fator=$Fator" -cp $classpath "rep04.$($classes[$Projeto])" @Argumentos
    if ($LASTEXITCODE -ne 0) { throw "O processo terminou com código $LASTEXITCODE" }
} finally {
    Pop-Location
}

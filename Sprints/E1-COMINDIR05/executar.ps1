param(
    [Parameter(Mandatory=$true)][ValidateSet('no','produtor','processador','raft','causal')][string]$Projeto,
    [string[]]$Argumentos = @(),
    [long]$Periodo = 2000, [int]$Fator = 3,
    [long]$EleicaoMin = 0, [long]$EleicaoMax = 0,
    [string]$Pasta = 'dados/manual', [string]$Api = 'http://127.0.0.1:5001/api/v0',
    [string]$Java = ''
)
$ErrorActionPreference = 'Stop'
if (-not $Java) { $Java = Join-Path (Split-Path -Parent (Get-Command javac).Source) 'java.exe' }
if ($EleicaoMin -eq 0) { $EleicaoMin = 3 * $Periodo }
if ($EleicaoMax -eq 0) { $EleicaoMax = 6 * $Periodo }
if ($Periodo -le 0 -or $Fator -le 0 -or $EleicaoMin -le 0 -or $EleicaoMax -lt $EleicaoMin) { throw 'Temporizadores invalidos' }
$classes = @{ no='PartitionNode'; produtor='Producer'; processador='AlarmProcessor'; raft='RaftCheck'; causal='CausalCheck' }
$project = if ($Projeto -in @('raft','causal')) { 'no' } else { $Projeto }
$classpath = Join-Path $PSScriptRoot "build/$project"
if (-not (Test-Path -LiteralPath "$classpath/raft04/$($classes[$Projeto]).class")) { throw 'Executa compilar.ps1 primeiro' }
$folder = if ([IO.Path]::IsPathRooted($Pasta)) { $Pasta } else { Join-Path $PSScriptRoot $Pasta }
New-Item -ItemType Directory -Path $folder -Force | Out-Null
Push-Location -LiteralPath $folder
try {
    & $Java "-Draft04.periodo=$Periodo" "-Draft04.fator=$Fator" "-Draft04.eleicaoMin=$EleicaoMin" "-Draft04.eleicaoMax=$EleicaoMax" "-Draft04.api=$Api" -cp $classpath "raft04.$($classes[$Projeto])" @Argumentos
    if ($LASTEXITCODE -ne 0) { throw "Processo terminou com codigo $LASTEXITCODE" }
} finally { Pop-Location }

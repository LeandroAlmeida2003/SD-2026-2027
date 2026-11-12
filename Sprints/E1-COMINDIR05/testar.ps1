param([string]$Javac = 'javac', [string]$Ipfs = '', [switch]$Completa)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'compilar.ps1') -Javac $Javac
$compiler = (Get-Command $Javac -ErrorAction Stop).Source
$java = Join-Path (Split-Path -Parent $compiler) 'java.exe'
$build = Join-Path $PSScriptRoot 'build/no'
& $Javac --release 11 -encoding UTF-8 -cp $build -d $build (Join-Path $PSScriptRoot 'testes/RaftStateTest.java') (Join-Path $PSScriptRoot 'testes/NodeRegressionTest.java') (Join-Path $PSScriptRoot 'testes/ReplayBatch.java')
if ($LASTEXITCODE -ne 0) { throw 'Falha a compilar testes' }
& $java '-Dfile.encoding=UTF-8' -cp $build raft04.RaftStateTest
if ($LASTEXITCODE -ne 0) { throw 'Falha nos testes dirigidos' }
$regressionFolder = Join-Path $PSScriptRoot ('dados/regressao-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $regressionFolder -Force | Out-Null
Push-Location -LiteralPath $regressionFolder
try {
    & $java '-Dfile.encoding=UTF-8' -cp $build raft04.NodeRegressionTest
    if ($LASTEXITCODE -ne 0) { throw 'Falha na regressao da truncagem' }
} finally { Pop-Location }
if ($Ipfs) {
    $suite = if ($Completa) { 'completa' } else { 'smoke' }
    & python (Join-Path $PSScriptRoot 'testes/ensaios.py') --ipfs $Ipfs --suite $suite
    if ($LASTEXITCODE -ne 0) { throw 'Falha nos ensaios de rede' }
}
& python (Join-Path $PSScriptRoot 'testes/mutacoes.py')
if ($LASTEXITCODE -ne 0) { throw 'Falha nas contraprovas das mutacoes' }

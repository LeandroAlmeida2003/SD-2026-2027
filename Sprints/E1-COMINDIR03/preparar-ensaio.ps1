param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-zA-Z0-9-]+$')]
    [string]$Nome,
    [Parameter(Mandatory = $true)]
    [string]$Anterior,
    [switch]$SemProcessador
)

# Preparação local: não envia leituras nem preenche resultados dos ensaios.
$ErrorActionPreference = 'Stop'
$trialRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$previousRun = [System.IO.Path]::GetFullPath((Join-Path $trialRoot $Anterior))
if (-not $previousRun.StartsWith($trialRoot + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'A execução anterior deve estar dentro da pasta da COMINDIR03.'
}
$trialRun = Join-Path $trialRoot "ensaios/$Nome"
if (Test-Path -LiteralPath $trialRun) {
    throw 'A pasta de destino já existe; os resultados não serão substituídos.'
}

# Confirma todas as identidades antes de terminar qualquer processo.
$previousEntries = Get-Content -Raw -LiteralPath (Join-Path $previousRun 'processos.json') | ConvertFrom-Json
$previousIds = New-Object 'System.Collections.Generic.List[int]'
foreach ($entry in $previousEntries) {
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $($entry.Id)"
    if (-not $processInfo) { continue }
    if ($processInfo.Name -ne 'java.exe' -or -not $processInfo.CommandLine.Contains($previousRun) -or
        $processInfo.CommandLine -notmatch 'rep03\.(PartitionReplica|PartitionLeader|AlarmProcessor)') {
        throw "O PID $($entry.Id) não corresponde ao processo esperado."
    }
    $previousIds.Add([int]$entry.Id)
}

New-Item -ItemType Directory -Path $trialRun -Force | Out-Null
[System.IO.File]::WriteAllText((Join-Path $trialRun '.gitignore'), "classes/`n*.log`nprocessos.json`n")
foreach ($role in @('produtor', 'lider', 'replica', 'processador')) {
    $classes = Join-Path $trialRun "classes/$role"
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $trialRoot "rep03-$role/src/rep03") -Filter '*.java' |
        Select-Object -ExpandProperty FullName)
    & javac --release 11 -encoding UTF-8 -d $classes @sources
    if ($LASTEXITCODE -ne 0) { throw "Compilação falhou: rep03-$role" }
}
foreach ($processId in $previousIds) {
    & taskkill.exe /PID ([string]$processId) /F
    if ($LASTEXITCODE -ne 0) { throw "Não foi possível terminar o PID $processId" }
}

$trialJava = (Get-Command java).Source
$trialProcesses = New-Object 'System.Collections.Generic.List[object]'
function Start-TrialProcess {
    param([string]$ProcessName, [string]$Role, [string[]]$JavaArguments)
    $classPath = '"' + (Join-Path $trialRun "classes/$Role") + '"'
    $launchArguments = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
        '-cp', $classPath) + $JavaArguments
    $process = Start-Process -FilePath $trialJava -ArgumentList $launchArguments -WorkingDirectory $trialRun `
        -WindowStyle Hidden -RedirectStandardOutput (Join-Path $trialRun "$ProcessName.log") `
        -RedirectStandardError (Join-Path $trialRun "$ProcessName-erro.log") -PassThru
    $trialProcesses.Add([pscustomobject]@{ Name = $ProcessName; Id = $process.Id })
    ConvertTo-Json -InputObject $trialProcesses.ToArray() -Depth 4 |
        Set-Content -Encoding UTF8 -LiteralPath (Join-Path $trialRun 'processos.json')
}

function Confirm-TrialProcesses {
    Start-Sleep -Milliseconds 700
    foreach ($entry in $trialProcesses) {
        $null = Get-Process -Id $entry.Id
        $log = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $trialRun ($entry.Name + '.log'))
        $errorLog = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $trialRun ($entry.Name + '-erro.log'))
        if ($errorLog -or $log -match 'terminada por falha|Não foi possível|Falha no líder|Entrada terminada|Subscrição terminada') {
            throw "Verificar $($entry.Name): $errorLog $log"
        }
    }
}

foreach ($replica in @(@{Id=1; Parts='0,1'}, @{Id=2; Parts='1,2'}, @{Id=3; Parts='2,0'})) {
    Start-TrialProcess -ProcessName "replica-$($replica.Id)" -Role 'replica' `
        -JavaArguments @('rep03.PartitionReplica', [string]$replica.Id, $replica.Parts, '5001')
}
Confirm-TrialProcesses
foreach ($partition in @(0, 1, 2)) {
    Start-TrialProcess -ProcessName "lider-p$partition" -Role 'lider' `
        -JavaArguments @('rep03.PartitionLeader', [string]$partition, '3', '127.0.0.1', 'http://127.0.0.1:5001/api/v0')
}
Confirm-TrialProcesses
if (-not $SemProcessador) {
    Start-TrialProcess -ProcessName 'processador' -Role 'processador' `
        -JavaArguments @('rep03.AlarmProcessor', '3', 'http://127.0.0.1:5001/api/v0')
    Confirm-TrialProcesses
}
Write-Output "Execução preparada: $trialRun"
$trialProcesses | Format-Table -AutoSize

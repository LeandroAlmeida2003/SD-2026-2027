param(
    [string]$Endereco = '127.0.0.1',
    [int]$PortoBase = 7000
)

# Aplica o método proposto pelo estudante às mensagens REG da execução local.
# Mede o texto do protocolo em UTF-8, antes do encapsulamento HTTP/PubSub.
$ErrorActionPreference = 'Stop'
$measurementRuns = @(
    @{ Name = '7c-1'; Folder = 'ensaios/7c-1-com-processador' },
    @{ Name = '7c-2'; Folder = 'ensaios/7c-2-com-processador' },
    @{ Name = '7c-3'; Folder = 'ensaios/7c-3-com-processador/amostra-7c' }
)
$measurementResults = New-Object 'System.Collections.Generic.List[object]'
foreach ($measurementRun in $measurementRuns) {
    [long]$messageCount = 0
    [long]$bytesWithClocks = 0
    [long]$bytesWithoutClocks = 0
    foreach ($partition in @(0, 1, 2)) {
        $recordPath = Join-Path $PSScriptRoot ($measurementRun.Folder + "/lider-p$partition.txt")
        foreach ($line in Get-Content -Encoding UTF8 -LiteralPath $recordPath) {
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            $fields = $line.Split(';')
            if ($fields.Length -ne 6) { throw "Registo mal formado: $recordPath" }
            $prefix = 'REG;' + $Endereco + ';' + ($PortoBase + $partition) + ';'
            $messageWithClocks = $prefix + $line
            $messageWithoutClocks = $prefix + [string]::Join(';', $fields[0..3])
            # GetByteCount UTF-8 é equivalente a getBytes(UTF_8).length em Java.
            $bytesWithClocks += [System.Text.Encoding]::UTF8.GetByteCount($messageWithClocks)
            $bytesWithoutClocks += [System.Text.Encoding]::UTF8.GetByteCount($messageWithoutClocks)
            $messageCount++
        }
    }
    if ($messageCount -eq 0) { throw "Execução sem registos: $($measurementRun.Name)" }
    $meanWithout = [double]$bytesWithoutClocks / $messageCount
    $meanWith = [double]$bytesWithClocks / $messageCount
    $measurementResults.Add([pscustomobject]@{
        Execucao = $measurementRun.Name
        Mensagens = $messageCount
        TotalBytesSemCarimbos = $bytesWithoutClocks
        TotalBytesComCarimbos = $bytesWithClocks
        MediaBytesSemCarimbos = $meanWithout
        MediaBytesComCarimbos = $meanWith
        DiferencaMediaBytes = $meanWith - $meanWithout
    })
}

$measurementResults
$meanWithoutAll = ($measurementResults | Measure-Object -Property MediaBytesSemCarimbos -Average).Average
$meanWithAll = ($measurementResults | Measure-Object -Property MediaBytesComCarimbos -Average).Average
[pscustomobject]@{
    Execucao = 'media-das-tres'
    Mensagens = $null
    TotalBytesSemCarimbos = $null
    TotalBytesComCarimbos = $null
    MediaBytesSemCarimbos = $meanWithoutAll
    MediaBytesComCarimbos = $meanWithAll
    DiferencaMediaBytes = $meanWithAll - $meanWithoutAll
}

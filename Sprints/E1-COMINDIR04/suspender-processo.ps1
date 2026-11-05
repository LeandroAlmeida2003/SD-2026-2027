param(
    [Parameter(Mandatory = $true)][int]$ProcessId,
    [Parameter(Mandatory = $true)][ValidateSet("Suspend", "Resume")][string]$Action,
    [ValidateRange(0, 3600)][int]$RetomarAposSegundos = 0
)

$ErrorActionPreference = "Stop"
if ($RetomarAposSegundos -gt 0 -and $Action -ne "Suspend") {
    throw "RetomarAposSegundos só se aplica a Suspend"
}
if (-not ("Rep04.NativeProcess" -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
namespace Rep04 {
    public static class NativeProcess {
        [DllImport("ntdll.dll")]
        public static extern int NtSuspendProcess(IntPtr handle);
        [DllImport("ntdll.dll")]
        public static extern int NtResumeProcess(IntPtr handle);
    }
}
'@
}
$targetProcess = Get-Process -Id $ProcessId
if ($Action -eq "Suspend") {
    $result = [Rep04.NativeProcess]::NtSuspendProcess($targetProcess.Handle)
} else {
    $result = [Rep04.NativeProcess]::NtResumeProcess($targetProcess.Handle)
}
if ($result -ne 0) { throw "Falha ao executar $Action no processo $ProcessId (NTSTATUS=$result)" }
Write-Host "$Action aplicado ao processo $ProcessId"
if ($RetomarAposSegundos -gt 0) {
    try {
        Start-Sleep -Seconds $RetomarAposSegundos
    } finally {
        $resumeResult = [Rep04.NativeProcess]::NtResumeProcess($targetProcess.Handle)
        if ($resumeResult -ne 0) { throw "Falha ao retomar o processo $ProcessId (NTSTATUS=$resumeResult)" }
        Write-Host "Resume aplicado ao processo $ProcessId"
    }
}

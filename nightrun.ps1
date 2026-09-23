# night-run.ps1 - keeps Claude Code working for up to 3 hours, then shuts down Windows.
#
# Usage (PowerShell, from your project folder):
#   cd C:\path\to\your\project
#   powershell -ExecutionPolicy Bypass -File .\night-run.ps1
#
# Cancel the shutdown at any time:  shutdown /a

$MaxHours = 3
$Prompt   = "Continue what you were working on. Do not ask me any questions - make reasonable decisions and keep going. When everything is finished, output a line containing exactly: ALL_DONE"
$Log      = Join-Path $HOME ("night-run-{0}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
$Deadline = (Get-Date).AddHours($MaxHours)

function Log($msg) { $msg | Tee-Object -FilePath $Log -Append }

# Schedule the shutdown up front, so the machine turns off even if this script dies.
$secs = [int]($MaxHours * 3600)
cmd /c "shutdown /a" 2>$null | Out-Null      # clear any previous schedule
cmd /c "shutdown /s /t $secs /c ""night-run finished"""

Log "Started : $(Get-Date)"
Log "Shutdown: $Deadline  (cancel with: shutdown /a)"
Log "Log file: $Log"
Log "------------------------------"

$iter = 0
while ((Get-Date) -lt $Deadline) {
    $iter++
    Log ""
    Log "=== Round $iter - $(Get-Date) ==="

    # --continue resumes the most recent Claude Code conversation in THIS folder.
    # If that session ended or crashed, the next round picks up where it left off.
    $out = (& claude --continue -p $Prompt --dangerously-skip-permissions 2>&1) | Out-String
    $code = $LASTEXITCODE
    Log $out

    if ($out -match 'ALL_DONE') {
        Log ">>> Claude reports it is done. Shutting down in 60s."
        cmd /c "shutdown /a" 2>$null | Out-Null
        cmd /c "shutdown /s /t 60 /c ""work complete"""
        break
    }

    if ($code -ne 0) {
        Log ">>> Session exited with code $code. Retrying in 30s."
        Start-Sleep -Seconds 30
    } else {
        Start-Sleep -Seconds 10
    }
}

Log ""
Log "Finished: $(Get-Date). Shutdown is scheduled - nothing else to do."

"""Check the Windows fixture runner's failure cleanup without a Docker daemon."""
from pathlib import Path
import shutil
import subprocess
import pytest

ROOT = Path(__file__).resolve().parents[3]


def test_failed_database_start_still_stops_only_owned_service(tmp_path):
    shell = shutil.which('pwsh') or shutil.which('powershell')
    if shell is None:
        pytest.skip('PowerShell fixture runner test requires PowerShell')
    runner = ROOT / 'apps/ai/tests/retrieval-env/run.ps1'
    log = tmp_path / 'docker-commands.txt'
    harness = tmp_path / 'start-failure.ps1'
    quote = lambda value: "'" + str(value).replace("'", "''") + "'"
    harness.write_text(
        "$global:commandLog = " + quote(log) + "\n"
        "function global:docker {\n"
        "  Add-Content -LiteralPath $global:commandLog -Value ($args -join ' ')\n"
        "  if ($args -contains 'up') { $global:LASTEXITCODE = 1 } else { $global:LASTEXITCODE = 0 }\n"
        "}\n"
        "& " + quote(runner) + "\nexit $LASTEXITCODE\n", encoding='utf-8')
    result = subprocess.run([shell, '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(harness)],capture_output=True,text=True,encoding="utf-8",errors="replace",timeout=30)
    assert result.returncode == 1
    commands = log.read_text(encoding='utf-8-sig').splitlines()
    assert len(commands)==2, 'partially failed startup must still invoke cleanup'
    assert ' -p researchdesk-retrieval ' in commands[1]
    assert commands[1].endswith(' stop test-db')
    assert all('down' not in command and '--remove-orphans' not in command for command in commands)

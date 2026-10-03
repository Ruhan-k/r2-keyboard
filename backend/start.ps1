$ErrorActionPreference = 'Stop'
$backendDir = $PSScriptRoot
$python = Join-Path $backendDir '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $python)) {
    throw 'Set up the backend virtual environment first; see README.md.'
}
Set-Location -LiteralPath $backendDir
# Local development only: load backend/.env into this process's environment.
# The backend itself reads GROQ_API_KEY / GROQ_MODEL from environment variables only.
$envFile = Join-Path $backendDir '.env'
if (Test-Path -LiteralPath $envFile) {
    Get-Content -LiteralPath $envFile | ForEach-Object {
        if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$' -and -not $_.TrimStart().StartsWith('#')) {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
        }
    }
}
& $python -m uvicorn main:app --host 127.0.0.1 --port 8000 --no-access-log

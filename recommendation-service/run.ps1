[CmdletBinding()]
param(
    [ValidateRange(1, 65535)]
    [int]$Port = 8092,
    [switch]$Reload
)

$ErrorActionPreference = 'Stop'
$pythonPath = Join-Path $PSScriptRoot '.venv\Scripts\python.exe'

if (-not (Test-Path -LiteralPath $pythonPath -PathType Leaf)) {
    throw 'Virtual environment not found. Create recommendation-service\.venv and install requirements.txt first.'
}

$uvicornArguments = @('-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', "$Port")
if ($Reload) {
    $uvicornArguments += '--reload'
}

Push-Location -LiteralPath $PSScriptRoot
try {
    & $pythonPath @uvicornArguments
    $applicationExitCode = $LASTEXITCODE
}
finally {
    Pop-Location
}

exit $applicationExitCode
